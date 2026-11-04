package io.wolftown.lauschig.ui.discover

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.browse.AuthorInfoCache
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterState
import io.wolftown.lauschig.ui.common.kindFilterState
import io.wolftown.lauschig.ui.common.toSummary
import io.wolftown.lauschig.ui.library.FilterPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** A series tile: the cover of its first volume, the name and the number of volumes. */
@Immutable
data class SeriesTile(val name: String, val count: Int, val cover: ItemSummary?)

/** An author entry; [authorId] is set when the optional author info knows an image for this name. */
@Immutable
data class AuthorTile(val name: String, val count: Int, val authorId: String?)

@Immutable
data class DiscoverUiState(
    val availableKinds: Set<LibraryKind> = emptySet(),
    val selectedKinds: Set<LibraryKind> = emptySet(),
    val sort: DiscoverSort = DiscoverSort.TITLE,
    val recent: List<ItemSummary> = emptyList(),
    val series: List<SeriesTile> = emptyList(),
    val genres: List<Facet> = emptyList(),
    val tags: List<Facet> = emptyList(),
    val authors: List<AuthorTile> = emptyList(),
    val all: List<ItemSummary> = emptyList(),
    /** The first data is not there yet (show neither content nor "nothing here"). */
    val loading: Boolean = false,
    /** The catalog has titles, but the kind filter hides all of them. */
    val hiddenByFilter: Boolean = false,
)

/** Store-like overview of the whole catalog, straight from Room (the optional author images are the only online extra). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel(
    accountStore: AccountStore,
    private val catalogDao: CatalogDao,
    private val itemDao: ItemDao,
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
    private val covers: CoverPathCache,
    private val prefs: FilterPreferences,
    private val discoverPrefs: DiscoverPreferences,
    private val authorInfo: AuthorInfoCache,
    private val catalog: Catalog,
    /** Where the grouping, sorting and tile mapping run; tests pass a test dispatcher. */
    private val dispatcher: CoroutineDispatcher,
    /** Starts a library sync now (the empty page's "Refresh library"). */
    private val requestSync: () -> Unit = {},
    /** Whether such a sync is waiting or running. */
    private val syncRunning: Flow<Boolean> = flowOf(false),
) : ViewModel() {

    @Inject constructor(
        accountStore: AccountStore, catalogDao: CatalogDao, itemDao: ItemDao, libraryDao: LibraryDao, progressDao: ProgressDao,
        downloadDao: DownloadDao, covers: CoverPathCache, prefs: FilterPreferences, discoverPrefs: DiscoverPreferences,
        authorInfo: AuthorInfoCache, catalog: Catalog, sync: SyncTrigger,
    ) : this(
        accountStore, catalogDao, itemDao, libraryDao, progressDao, downloadDao, covers, prefs, discoverPrefs, authorInfo, catalog,
        Dispatchers.Default, sync::syncNow, sync.nowRunning(),
    )

    private val serverId: Flow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()

    init {
        viewModelScope.launch { serverId.collect { sid -> if (sid != null) authorInfo.ensureLoaded(sid) } }
    }

    val state: StateFlow<DiscoverUiState> = serverId
        .flatMapLatest { sid -> if (sid == null) flowOf(DiscoverUiState()) else stateFor(sid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiscoverUiState(loading = true))

    fun setKinds(kinds: Set<LibraryKind>) {
        viewModelScope.launch { prefs.setKinds(SCREEN, kinds) }
    }

    private val _refreshing = MutableStateFlow(false)

    /** A refresh started here is under way: until the sync has run (or [REFRESH_TIMEOUT_MS], e.g. offline). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Starts a library sync unless one is already waiting or running (it is not restarted), and shows it. */
    fun refreshLibrary() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                if (!syncRunning.first()) requestSync()
                withTimeoutOrNull(REFRESH_TIMEOUT_MS) { syncRunning.dropWhile { !it }.first { !it } }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun setSort(sort: DiscoverSort) {
        viewModelScope.launch { discoverPrefs.setSort(sort) }
    }

    /**
     * The grouping and sorting only rerun when the catalog, the kind filter or the sort change; progress and download
     * changes (progress is written every few seconds while playing) only redo the cheap tile mapping.
     */
    private fun stateFor(sid: String): Flow<DiscoverUiState> {
        val sections = combine(catalogDao.observeAll(sid), kindFilterState(libraryDao, prefs, sid, SCREEN), discoverPrefs.sort) { all, f, sort ->
            sections(f, sort, all.filter { it.libraryId in f.libraryIds }, unfiltered = all.size)
        }.flowOn(dispatcher)
        val itemState = combine(progressDao.observeAll(sid), itemDao.observeDownloaded(sid), downloadDao.observeActiveIds(sid), ::Triple)
        val authorIds = authorInfo.authors.map { known ->
            known.entries.filter { it.key.startsWith("$sid/") && it.value.imagePath != null }.associate { it.key to it.value.id }
        }.distinctUntilChanged()
        return combine(sections, itemState, authorIds) { s, (progress, downloaded, active), images ->
            val byId = progress.associateBy { it.itemId }
            val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
            covers.sync(downloadedIds)
            val activeIds = active.toHashSet()
            fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)
            DiscoverUiState(
                s.filter.available, s.filter.selected, s.sort,
                recent = s.recent.map { it.summary() },
                series = s.series.map { (facet, cover) -> SeriesTile(facet.name, facet.count, cover?.summary()) },
                genres = s.genres,
                tags = s.tags,
                authors = s.authors.map { AuthorTile(it.name, it.count, images[AuthorInfoCache.key(sid, it.name)]) },
                all = s.all.map { it.summary() },
                hiddenByFilter = s.hiddenByFilter,
            )
        }.flowOn(dispatcher)
    }

    private class Sections(
        val filter: KindFilterState,
        val sort: DiscoverSort,
        val recent: List<ItemEntity>,
        val series: List<Pair<Facet, ItemEntity?>>,
        val genres: List<Facet>,
        val tags: List<Facet>,
        val authors: List<Facet>,
        val all: List<ItemEntity>,
        val hiddenByFilter: Boolean,
    )

    private fun sections(filter: KindFilterState, sort: DiscoverSort, visible: List<ItemEntity>, unfiltered: Int): Sections {
        val facets = catalog.facets(visible)
        val byId = visible.associateBy { it.id }
        val recent = visible.filter { it.addedAt > 0 }
            .sortedWith(compareByDescending<ItemEntity> { it.addedAt }.thenBy { it.title.lowercase() }).take(ROW_LIMIT)
        return Sections(
            filter, sort, recent,
            series = facets.series.take(ROW_LIMIT).map { it to it.coverItemId?.let(byId::get) },
            genres = facets.genres,
            tags = facets.tags,
            authors = facets.authors.take(ROW_LIMIT),
            all = sorted(visible, sort),
            hiddenByFilter = visible.isEmpty() && unfiltered > 0,
        )
    }

    private fun sorted(items: List<ItemEntity>, sort: DiscoverSort): List<ItemEntity> {
        val byTitle = compareBy<ItemEntity> { it.title.lowercase() }
        return when (sort) {
            DiscoverSort.TITLE -> items.sortedWith(byTitle)
            DiscoverSort.AUTHOR -> items.sortedWith(
                compareBy<ItemEntity> { catalog.splitPeople(it.author).isEmpty() }
                    .thenBy { catalog.splitPeople(it.author).firstOrNull()?.lowercase().orEmpty() }
                    .then(byTitle),
            )
            DiscoverSort.ADDED -> items.sortedWith(compareByDescending<ItemEntity> { it.addedAt }.then(byTitle))
            DiscoverSort.DURATION -> items.sortedWith(compareByDescending<ItemEntity> { it.durationSec }.then(byTitle))
        }
    }

    companion object {
        const val SCREEN = "discover"
        const val ROW_LIMIT = 20
        const val REFRESH_TIMEOUT_MS = 60_000L
    }
}
