package io.wolftown.lauschig.ui.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.toSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * [Collections] and [Playlists] show the cached ABS lists instead of titles (see `CollectionListTab`); [Device] shows the
 * device library ("Auf dem Gerät", see `DeviceTab`).
 */
enum class LibraryTab { InProgress, Finished, Downloaded, Series, Collections, Playlists, Device }

@Immutable
data class SeriesGroup(val name: String, val items: List<ItemSummary>)

@Immutable
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.InProgress,
    /** Kinds present among the user's libraries (podcasts excluded in Phase 1). */
    val availableKinds: Set<LibraryKind> = emptySet(),
    /** Explicit selection; empty means "All". */
    val selectedKinds: Set<LibraryKind> = emptySet(),
    /** Flat list for every tab except [LibraryTab.Series]. */
    val items: List<ItemSummary> = emptyList(),
    /** Only filled for [LibraryTab.Series]. */
    val series: List<SeriesGroup> = emptyList(),
    /** False until the first Room emission, so the empty hint does not flash. */
    val loaded: Boolean = false,
    /** Tags of the tab's titles (after the kind filter), most titles first. */
    val availableTags: List<Facet> = emptyList(),
    /** The tag filter, null for all; a chosen tag the tab does not have counts as null. */
    val selectedTag: String? = null,
    /** The tab has titles, but the kind or tag filter hides all of them (the empty page says so and offers a reset). */
    val hiddenByFilter: Boolean = false,
)

/** Library data straight from Room; the connection state only decides dimming/cover source in the UI. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    accountStore: AccountStore,
    private val itemDao: ItemDao,
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val covers: CoverPathCache,
    private val prefs: FilterPreferences,
    private val downloadDao: DownloadDao,
    private val catalog: Catalog = Catalog(),
) : ViewModel() {
    private val tab = MutableStateFlow(LibraryTab.InProgress)
    private val tag = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryUiState> = accountStore.account
        .map { it?.serverId }
        .distinctUntilChanged()
        // Signed out (on the way to the sign-in): nothing to load, so no endless "loading".
        .flatMapLatest { sid -> if (sid == null) flowOf(LibraryUiState(loaded = true)) else stateFor(sid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun selectTab(t: LibraryTab) {
        tab.value = t
    }

    /** Session-only (not stored): a forgotten tag filter must not make the library look empty next time. */
    fun selectTag(t: String?) {
        tag.value = t?.trim()?.takeIf(String::isNotEmpty)
    }

    fun setKinds(kinds: Set<LibraryKind>) {
        viewModelScope.launch { prefs.setKinds(SCREEN, kinds) }
    }

    private fun stateFor(sid: String): Flow<LibraryUiState> {
        val filter = combine(libraryDao.observeAll(sid), prefs.kinds(SCREEN)) { libs, stored ->
            val available = libs.map { it.effectiveKind }.toSet()
            val selected = stored.intersect(available)
            val effective = selected.ifEmpty { available }
            Filter(
                available = available,
                selected = selected,
                effective = effective,
                libraryIds = libs.filter { it.effectiveKind in effective }.map { it.id }.toSet(),
            )
        }.distinctUntilChanged()

        return combine(filter, tab, ::Pair).flatMapLatest { (f, t) ->
            val source: Flow<List<ItemEntity>> = when (t) {
                LibraryTab.InProgress -> itemDao.observeInProgress(sid)
                LibraryTab.Finished -> itemDao.observeFinished(sid)
                LibraryTab.Downloaded -> itemDao.observeDownloaded(sid)
                LibraryTab.Series -> itemDao.observeByLibraryKinds(sid, f.effective.toList())
                LibraryTab.Collections, LibraryTab.Playlists, LibraryTab.Device -> flowOf(emptyList())
            }
            val rows = combine(source, progressDao.observeAll(sid), itemDao.observeDownloaded(sid), downloadDao.observeActiveIds(sid), ::Rows)
            combine(rows, tag) { (items, progress, downloaded, active), wanted ->
                val byId = progress.associateBy { it.itemId }
                val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
                covers.sync(downloadedIds)
                val inTab = if (t == LibraryTab.Series) items.filter { !it.seriesName.isNullOrBlank() } else items.filter { it.libraryId in f.libraryIds }
                val tags = catalog.tags(inTab)
                val selected = wanted?.let { w -> tags.firstOrNull { it.name.equals(w, ignoreCase = true) } }?.let { wanted }
                val visible = if (selected == null) inTab else inTab.filter { i -> i.tags.any { it.trim().equals(selected, ignoreCase = true) } }
                val activeIds = active.toHashSet()
                fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)
                // Blame the filter only when it hides titles the tab has (Series reads its titles already kind-filtered,
                // so there only the tag can be blamed).
                val unfiltered = if (t == LibraryTab.Series) inTab else items
                val hidden = visible.isEmpty() && unfiltered.isNotEmpty()
                if (t == LibraryTab.Series) {
                    LibraryUiState(t, f.available, f.selected, emptyList(), groupSeries(visible).map { (n, l) -> SeriesGroup(n, l.map { it.summary() }) }, true, tags, selected, hidden)
                } else {
                    LibraryUiState(t, f.available, f.selected, visible.map { it.summary() }, emptyList(), true, tags, selected, hidden)
                }
            }.flowOn(Dispatchers.Default).distinctUntilChanged()
        }
    }

    private data class Rows(
        val items: List<ItemEntity>,
        val progress: List<io.wolftown.lauschig.db.entities.ProgressEntity>,
        val downloaded: List<ItemEntity>,
        val active: List<String>,
    )

    private data class Filter(
        val available: Set<LibraryKind>,
        val selected: Set<LibraryKind>,
        val effective: Set<LibraryKind>,
        val libraryIds: Set<String>,
    )

    companion object {
        const val SCREEN = "library"
    }
}

private val bySequence: Comparator<ItemEntity> =
    compareBy<ItemEntity, Double?>(nullsLast()) { it.seriesSortKey }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }

/**
 * Groups by series name (case-insensitive; items without a series are dropped). Groups are sorted by name,
 * volumes by sort key ascending with unknown keys last, then by title.
 */
internal fun groupSeries(items: List<ItemEntity>): List<Pair<String, List<ItemEntity>>> =
    items.filter { !it.seriesName.isNullOrBlank() }
        .groupBy { it.seriesName!!.trim().lowercase() }
        .values
        .map { group ->
            val sorted = group.sortedWith(bySequence)
            sorted.first().seriesName!!.trim() to sorted
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
