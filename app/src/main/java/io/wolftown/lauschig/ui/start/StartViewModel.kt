package io.wolftown.lauschig.ui.start

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.toSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Start screen data straight from Room (offline-first: no network on this path). Sections: continue listening,
 * next in your series, new on the server, downloaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StartViewModel(
    private val covers: CoverPathCache,
    private val accountStore: AccountStore,
    private val catalogDao: CatalogDao,
    private val libraryDao: LibraryDao,
    private val itemDao: ItemDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
    private val player: PlayerController,
    private val catalog: Catalog,
    /** Where grouping and tile mapping run; tests pass a test dispatcher. */
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject constructor(
        covers: CoverPathCache, accountStore: AccountStore, catalogDao: CatalogDao, libraryDao: LibraryDao, itemDao: ItemDao,
        progressDao: ProgressDao, downloadDao: DownloadDao, player: PlayerController, catalog: Catalog,
    ) : this(covers, accountStore, catalogDao, libraryDao, itemDao, progressDao, downloadDao, player, catalog, Dispatchers.Default)

    private var serverId: String? = null

    val state: StateFlow<StartUiState> = accountStore.account
        .flatMapLatest { account ->
            serverId = account?.serverId
            if (account == null) flowOf(StartUiState()) else stateFor(account.serverId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    /** Catalog-derived data that does not depend on playback progress, recomputed only when the catalog changes. */
    private class CatalogView(val recent: List<ItemEntity>, val seriesGroups: List<List<ItemEntity>>)

    private fun stateFor(sid: String): Flow<StartUiState> {
        // The grouping runs on catalog changes only; progress writes (every few seconds while playing) only re-pick.
        val catalogView = combine(catalogDao.observeAll(sid), libraryDao.observeAll(sid)) { items, libs ->
            // Podcast libraries are not browsable yet (same rule as Discover, Search and Library).
            val podcasts = libs.filter { it.effectiveKind == LibraryKind.PODCAST }.mapTo(HashSet()) { it.id }
            val all = items.filter { it.libraryId !in podcasts }
            CatalogView(
                recent = all.filter { it.addedAt > 0 }
                    .sortedWith(compareByDescending<ItemEntity> { it.addedAt }.thenBy { it.title.lowercase() }).take(ROW_LIMIT),
                seriesGroups = catalog.seriesGroups(all),
            )
        }.flowOn(dispatcher)
        val itemState = combine(
            itemDao.observeInProgress(sid), itemDao.observeDownloaded(sid), progressDao.observeAll(sid), downloadDao.observeActiveIds(sid), ::Four,
        )
        return combine(catalogView, itemState) { view, (inProgress, downloaded, progress, active) ->
            val byId = progress.associateBy { it.itemId }
            val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
            covers.sync(downloadedIds)
            val activeIds = active.toHashSet()
            fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)
            StartUiState(
                continueItems = inProgress.filter { byId[it.id]?.hideFromContinue != true }.take(ROW_LIMIT).map { it.summary() },
                nextInSeries = catalog.nextInSeries(view.seriesGroups, byId, ROW_LIMIT).map { it.summary() },
                recentlyAdded = view.recent.map { it.summary() },
                downloaded = downloaded.map { it.summary() },
            )
        }.flowOn(dispatcher).distinctUntilChanged()
    }

    private data class Four<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    fun play(itemId: String) {
        serverId?.let { player.play(it, itemId) }
    }

    companion object {
        const val ROW_LIMIT = 10
    }
}
