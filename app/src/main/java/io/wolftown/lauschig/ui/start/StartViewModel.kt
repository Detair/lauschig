package io.wolftown.lauschig.ui.start

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.common.ItemSummary
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
    private val episodeDao: EpisodeDao,
    private val player: PlayerController,
    private val catalog: Catalog,
    /** Where grouping and tile mapping run; tests pass a test dispatcher. */
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject constructor(
        covers: CoverPathCache, accountStore: AccountStore, catalogDao: CatalogDao, libraryDao: LibraryDao, itemDao: ItemDao,
        progressDao: ProgressDao, downloadDao: DownloadDao, episodeDao: EpisodeDao, player: PlayerController, catalog: Catalog,
    ) : this(covers, accountStore, catalogDao, libraryDao, itemDao, progressDao, downloadDao, episodeDao, player, catalog, Dispatchers.Default)

    private var serverId: String? = null

    val state: StateFlow<StartUiState> = accountStore.account
        .flatMapLatest { account ->
            serverId = account?.serverId
            if (account == null) flowOf(StartUiState()) else stateFor(account.serverId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartUiState())

    /** Catalog-derived data that does not depend on playback progress, recomputed only when the catalog changes. */
    private class CatalogView(val recent: List<ItemEntity>, val seriesGroups: List<List<ItemEntity>>, val podcasts: Map<String, ItemEntity>?)

    private fun stateFor(sid: String): Flow<StartUiState> {
        // The grouping runs on catalog changes only; progress writes (every few seconds while playing) only re-pick.
        val catalogView = combine(catalogDao.observeAll(sid), libraryDao.observeAll(sid)) { items, libs ->
            // Podcast items stay out of the book rows; their episodes have their own row.
            val podcastLibs = libs.filter { it.effectiveKind == LibraryKind.PODCAST }.mapTo(HashSet()) { it.id }
            val all = items.filter { it.libraryId !in podcastLibs }
            CatalogView(
                recent = all.filter { it.addedAt > 0 }
                    .sortedWith(compareByDescending<ItemEntity> { it.addedAt }.thenBy { it.title.lowercase() }).take(ROW_LIMIT),
                seriesGroups = catalog.seriesGroups(all),
                podcasts = if (podcastLibs.isEmpty()) null else items.filter { it.libraryId in podcastLibs }.associateBy { it.id },
            )
        }.flowOn(dispatcher)
        val itemState = combine(
            itemDao.observeInProgress(sid), itemDao.observeDownloaded(sid), progressDao.observeAll(sid), downloadDao.observeActiveIds(sid), ::Four,
        )
        val episodeState = combine(
            episodeDao.observeStarted(sid, NEW_EPISODES_LIMIT), episodeDao.observeRecent(sid, NEW_EPISODES_LIMIT * 4), downloadDao.observeCompleteIds(sid),
            ::Triple,
        )
        // Titles of the device library that are being heard join "continue listening" (always playable, never synced).
        val dev = DeviceLibrary.SERVER_ID
        val deviceState = combine(itemDao.observeInProgress(dev), progressDao.observeAll(dev), ::Pair)
        return combine(catalogView, itemState, episodeState, deviceState) { view, (inProgress, downloaded, progress, active), (started, recent, completeKeys), (deviceItems, deviceProgress) ->
            val byId = progress.associateBy { it.itemId }
            val deviceById = deviceProgress.associateBy { it.itemId }
            val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
            covers.sync(downloadedIds + view.podcasts?.keys.orEmpty() + deviceItems.map { "${it.id}@${it.coverUpdatedAt}" })
            val activeIds = active.toHashSet()
            fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)
            val continueServer = inProgress.filter { byId[it.id]?.hideFromContinue != true }.map { Triple(it, byId[it.id]?.lastUpdate ?: 0L, false) }
            val continueDevice = deviceItems.filter { deviceById[it.id]?.hideFromContinue != true }.map { Triple(it, deviceById[it.id]?.lastUpdate ?: 0L, true) }
            StartUiState(
                continueItems = (continueServer + continueDevice).sortedByDescending { it.second }.take(ROW_LIMIT).map { (item, _, device) ->
                    if (device) item.toSummary(covers, deviceById[item.id], downloaded = true) else item.summary()
                },
                nextInSeries = catalog.nextInSeries(view.seriesGroups, byId, ROW_LIMIT).map { it.summary() },
                recentlyAdded = view.recent.map { it.summary() },
                downloaded = downloaded.map { it.summary() },
                newEpisodes = view.podcasts?.let { newEpisodes(sid, it, started, recent, byId, completeKeys.toHashSet(), activeIds) } ?: emptyList(),
            )
        }.flowOn(dispatcher).distinctUntilChanged()
    }

    /**
     * Started episodes first, then recent ones that were not touched yet, at most [NEW_EPISODES_LIMIT]. The tile is the
     * podcast's cover with the episode title; its id is the episode key.
     */
    private fun newEpisodes(
        sid: String, podcasts: Map<String, ItemEntity>, started: List<EpisodeEntity>, recent: List<EpisodeEntity>,
        progress: Map<String, ProgressEntity>, complete: Set<String>, active: Set<String>,
    ): List<ItemSummary> {
        fun EpisodeEntity.key() = PlayableKey.episode(itemId, id)
        val startedKeys = started.mapTo(HashSet()) { it.key() }
        val unheard = recent.filter { e -> e.key().let { it !in startedKeys && progress[it]?.let { p -> p.isFinished || p.currentTimeSec > 0 } != true } }
        return (started + unheard).mapNotNull { e ->
            val podcast = podcasts[e.itemId] ?: return@mapNotNull null
            val key = e.key()
            val p = progress[key]
            val duration = p?.durationSec?.takeIf { it > 0 } ?: e.durationSec
            ItemSummary(
                serverId = sid, id = key, title = e.title, author = podcast.title, seriesName = null, seriesSequence = null,
                coverPath = covers.pathFor(sid, podcast.id),
                progress = if (p == null || duration <= 0) 0f else (p.currentTimeSec / duration).toFloat().coerceIn(0f, 1f),
                isDownloaded = key in complete, isFinished = false, isDownloading = key in active && key !in complete,
                coverVersion = podcast.coverUpdatedAt,
            )
        }.take(NEW_EPISODES_LIMIT)
    }

    private data class Four<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** The loaded title while it plays, else null (the "continue" card shows pause for it). */
    val playingId: StateFlow<String?> = player.state.map { p -> p.itemId?.takeIf { p.isPlaying } }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(itemId: String) {
        DeviceLibrary.serverFor(serverId, itemId)?.let { player.play(it, itemId) }
    }

    /** The "continue" card's button: pauses or resumes [itemId] when it is the loaded title, else starts it. */
    fun playOrPause(itemId: String) {
        if (player.state.value.itemId == itemId) player.togglePlayPause() else play(itemId)
    }

    companion object {
        const val ROW_LIMIT = 10
        const val NEW_EPISODES_LIMIT = 15
    }
}
