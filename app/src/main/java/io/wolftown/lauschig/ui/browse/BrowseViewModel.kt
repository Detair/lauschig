package io.wolftown.lauschig.ui.browse

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.absapi.library.AuthorDto
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.catalog.PersonRole
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.external.MissingVolume
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.kindFilterState
import io.wolftown.lauschig.ui.common.toSummary
import io.wolftown.lauschig.ui.library.FilterPreferences
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One cell of an entity page: a title we have, or (series only) a gap in the volume numbers. */
sealed interface BrowseEntry {
    @Immutable data class Tile(val item: ItemSummary) : BrowseEntry
    /** [volume]: the Audible volume behind the gap, once looked up (online, setting on). */
    @Immutable data class Missing(val sequence: String, val volume: MissingVolume? = null) : BrowseEntry
}

@Immutable
data class BrowseUiState(
    val kind: BrowseKind = BrowseKind.AUTHOR,
    val name: String = "",
    val availableKinds: Set<LibraryKind> = emptySet(),
    val selectedKinds: Set<LibraryKind> = emptySet(),
    val entries: List<BrowseEntry> = emptyList(),
    /** Number of titles (placeholders not counted). */
    val count: Int = 0,
    /** Author pages: series of the author's titles, for the "Series" row. */
    val series: List<Facet> = emptyList(),
    /** Author pages: image/bio, once loaded online. */
    val author: AuthorDto? = null,
    /** Series pages: ask once whether Audible may be asked for the missing volumes. */
    val askMissingVolumes: Boolean = false,
    /** False until the first Room emission, so the empty hint does not flash. */
    val loaded: Boolean = false,
)

/** Entity page data straight from Room; only the optional author extras ever touch the network (in [AuthorInfoCache]). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel(
    private val kind: BrowseKind,
    private val name: String,
    private val accountStore: AccountStore,
    private val catalogDao: CatalogDao,
    private val itemDao: ItemDao,
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
    private val covers: CoverPathCache,
    private val prefs: FilterPreferences,
    private val catalog: Catalog,
    private val authorInfo: AuthorInfoCache,
    private val missingVolumes: MissingVolumesSource,
) : ViewModel() {

    @Inject constructor(
        savedState: SavedStateHandle,
        accountStore: AccountStore,
        catalogDao: CatalogDao,
        itemDao: ItemDao,
        libraryDao: LibraryDao,
        progressDao: ProgressDao,
        downloadDao: DownloadDao,
        covers: CoverPathCache,
        prefs: FilterPreferences,
        catalog: Catalog,
        authorInfo: AuthorInfoCache,
        missingVolumes: MissingVolumesSource,
    ) : this(
        savedState.toRoute<Browse>().kind, savedState.toRoute<Browse>().name,
        accountStore, catalogDao, itemDao, libraryDao, progressDao, downloadDao, covers, prefs, catalog, authorInfo, missingVolumes,
    )

    private val serverId: Flow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()

    init {
        if (kind == BrowseKind.AUTHOR) {
            viewModelScope.launch { serverId.collect { sid -> if (sid != null) authorInfo.ensureLoaded(sid) } }
        }
    }

    val state: StateFlow<BrowseUiState> = serverId
        // Signed out (on the way to the sign-in): nothing to load, so no endless "loading".
        .flatMapLatest { sid -> if (sid == null) flowOf(BrowseUiState(kind, name, loaded = true)) else stateFor(sid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(kind, name))

    /** The user's answer on a series page; the catalog is asked from now on only after a yes. */
    fun answerMissingVolumes(allow: Boolean) {
        viewModelScope.launch { missingVolumes.answer(allow) }
    }

    fun setKinds(kinds: Set<LibraryKind>) {
        viewModelScope.launch { prefs.setKinds(SCREEN, kinds) }
    }

    private fun stateFor(sid: String): Flow<BrowseUiState> {
        val filter = kindFilterState(libraryDao, prefs, sid, SCREEN)

        val author = authorInfo.authors.map { it[AuthorInfoCache.key(sid, name)] }.distinctUntilChanged()

        // One Room observer for both the lookup and the page.
        val catalogRows = catalogDao.observeAll(sid).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

        // Audible lookups run apart from Room so the plain page shows first; null = no data (offline, off, failed).
        val missing: Flow<List<MissingVolume>?> =
            if (kind != BrowseKind.SERIES) flowOf(null)
            else combine(catalogRows, filter) { all, f -> ownedByLibrary(all.filter { it.libraryId in f.libraryIds }) }
                .distinctUntilChanged()
                .flatMapLatest { missingVolumes.forSeries(sid, it) }
                .onStart { emit(null) }

        // Only where a lookup would really send something: a title of this series with an ASIN.
        val ask: Flow<Boolean> =
            if (kind != BrowseKind.SERIES) flowOf(false)
            else combine(catalogRows, filter, missingVolumes.undecided) { all, f, undecided ->
                undecided && ownedByLibrary(all.filter { it.libraryId in f.libraryIds }).values.flatten().any { !it.asin.isNullOrBlank() }
            }.distinctUntilChanged()

        return combine(
            combine(catalogRows, missing, ::Pair), filter, progressDao.observeAll(sid), itemDao.observeDownloaded(sid), downloadDao.observeActiveIds(sid),
        ) { (all, audible), f, progress, downloaded, active ->
            val byId = progress.associateBy { it.itemId }
            val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
            covers.sync(downloadedIds)
            val activeIds = active.toHashSet()
            fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)

            val visible = all.filter { it.libraryId in f.libraryIds }
            val entries: List<BrowseEntry> = when (kind) {
                BrowseKind.SERIES -> mergeMissing(catalog.bySeries(visible, name), audible) { it.summary() }
                else -> pageItems(visible).map { BrowseEntry.Tile(it.summary()) }
            }
            val series = if (kind == BrowseKind.AUTHOR) catalog.facets(pageItems(visible)).series else emptyList()
            BrowseUiState(
                kind, name, f.available, f.selected, entries,
                count = entries.count { it is BrowseEntry.Tile }, series = series, loaded = true,
            )
        }.flowOn(Dispatchers.Default).combine(author) { s, a -> s.copy(author = a) }
            .combine(ask) { s, a -> s.copy(askMissingVolumes = a) }.distinctUntilChanged()
    }

    /** This page's titles per library; Audible series are looked up per (library, series name). */
    private fun ownedByLibrary(visible: List<ItemEntity>): Map<String, List<ItemEntity>> {
        val key = name.trim().lowercase()
        return visible.filter { it.seriesName?.trim()?.lowercase() == key }.groupBy { it.libraryId }
    }

    private fun pageItems(items: List<ItemEntity>): List<ItemEntity> = when (kind) {
        BrowseKind.AUTHOR -> catalog.byPerson(items, PersonRole.AUTHOR, name)
        BrowseKind.NARRATOR -> catalog.byPerson(items, PersonRole.NARRATOR, name)
        BrowseKind.GENRE -> catalog.byGenre(items, name)
        BrowseKind.TAG -> catalog.byTag(items, name)
        BrowseKind.SERIES -> emptyList()
    }

    companion object {
        /** One saved filter shared by all entity pages. */
        const val SCREEN = "browse"
    }
}
