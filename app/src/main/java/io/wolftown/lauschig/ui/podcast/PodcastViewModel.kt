package io.wolftown.lauschig.ui.podcast

import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.nav.ItemDetail
import kotlinx.coroutines.CancellationException
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

enum class EpisodeFilter { ALL, UNPLAYED, DOWNLOADED }

/** One episode row. [key] is the playable key (`podcast~episode`) under which progress and downloads are stored. */
@Immutable
data class EpisodeUi(
    val key: String,
    val title: String,
    /** Epoch ms, 0 = unknown. */
    val publishedAt: Long,
    val durationSec: Double,
    /** Time left, only for a started, unfinished episode. */
    val remainingSec: Double?,
    /** 0f..1f, only for a started, unfinished episode. */
    val progress: Float,
    val finished: Boolean,
)

@Immutable
data class PodcastUiState(
    val found: Boolean = false,
    val itemId: String = "",
    val title: String = "",
    val author: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val coverVersion: Long = 0,
    /** Episodes the server reports (falls back to the stored count). */
    val episodeCount: Int = 0,
    /** Episodes passing [filter], newest first. */
    val episodes: List<EpisodeUi> = emptyList(),
    val filter: EpisodeFilter = EpisodeFilter.ALL,
    /** Stored episodes may be fewer than the podcast has; offline the screen says more come once online. */
    val incomplete: Boolean = false,
    /** The episodes are being fetched. */
    val loading: Boolean = false,
)

/** Podcast page data from Room; the network is only used for one background [loadEpisodes] when the episodes are missing. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastViewModel(
    private val itemId: String,
    private val accountStore: AccountStore,
    private val itemDao: ItemDao,
    private val episodeDao: EpisodeDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
    private val player: PlayerController,
    connection: Flow<ConnectionState>,
    private val loadEpisodes: suspend (serverId: String, itemId: String) -> Unit,
    private val covers: CoverPathCache,
) : ViewModel() {

    @Inject constructor(
        savedState: SavedStateHandle,
        accountStore: AccountStore,
        itemDao: ItemDao,
        episodeDao: EpisodeDao,
        progressDao: ProgressDao,
        downloadDao: DownloadDao,
        player: PlayerController,
        api: ApiProvider,
        sync: LibrarySync,
        covers: CoverPathCache,
    ) : this(
        savedState.toRoute<ItemDetail>().itemId, accountStore, itemDao, episodeDao, progressDao, downloadDao, player, api.connection,
        sync::loadEpisodes, covers,
    )

    private enum class Load { IDLE, RUNNING, DONE, FAILED }

    @Volatile private var serverId: String? = null
    private var load = Load.IDLE
    private val loading = MutableStateFlow(false)
    private val filter = MutableStateFlow(EpisodeFilter.ALL)

    private val online: Flow<Boolean> = connection.map { it is ConnectionState.Online }.distinctUntilChanged()
    private val account = accountStore.account.map { it?.serverId }.distinctUntilChanged()

    init {
        // One attempt per screen instance; only a failed attempt is retried, and only when the inputs change (e.g. offline -> online).
        viewModelScope.launch {
            account.flatMapLatest { sid ->
                if (sid == null) flowOf(Triple(null, false, false))
                else combine(online, itemDao.observeItemRow(sid, itemId).map { it?.detailsLoaded }.distinctUntilChanged()) { on, loaded ->
                    Triple(sid, on, loaded == false)
                }
            }.distinctUntilChanged().collect { (sid, on, missing) ->
                if (sid != null && on && missing && (load == Load.IDLE || load == Load.FAILED)) {
                    load = Load.RUNNING
                    loading.value = true
                    launch {
                        load = try {
                            loadEpisodes(sid, itemId); Load.DONE
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Load.FAILED
                        } finally {
                            loading.value = false
                        }
                    }
                }
            }
        }
    }

    val state: StateFlow<PodcastUiState> = account
        .flatMapLatest { sid ->
            serverId = sid
            if (sid == null) flowOf(PodcastUiState(itemId = itemId))
            else {
                val progress = progressDao.observeAll(sid)
                    .map { all -> all.filter { it.itemId.startsWith("$itemId~") }.associateBy { it.itemId } }
                    .distinctUntilChanged()
                val data = combine(
                    itemDao.observeItemRow(sid, itemId), episodeDao.observeForItem(sid, itemId), progress, downloadDao.observeCompleteIds(sid),
                ) { item, episodes, byKey, complete -> Data(item, episodes, byKey, complete.toHashSet()) }
                combine(data, filter, loading) { d, f, l -> build(sid, d, f, l) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastUiState(itemId = itemId))

    private class Data(
        val item: io.wolftown.lauschig.db.entities.ItemEntity?,
        val episodes: List<EpisodeEntity>,
        val progress: Map<String, io.wolftown.lauschig.db.entities.ProgressEntity>,
        val complete: Set<String>,
    )

    private fun build(sid: String, d: Data, filter: EpisodeFilter, loading: Boolean): PodcastUiState {
        val item = d.item ?: return PodcastUiState(itemId = itemId, filter = filter)
        val rows = d.episodes.map { e ->
            val key = PlayableKey.episode(itemId, e.id)
            val p = d.progress[key]
            val duration = p?.durationSec?.takeIf { it > 0 } ?: e.durationSec
            val started = p != null && !p.isFinished && p.currentTimeSec > 0
            EpisodeUi(
                key = key, title = e.title, publishedAt = e.publishedAt, durationSec = duration,
                remainingSec = if (started && duration > 0) (duration - p!!.currentTimeSec).coerceAtLeast(0.0) else null,
                progress = if (started && duration > 0) (p!!.currentTimeSec / duration).toFloat().coerceIn(0f, 1f) else 0f,
                finished = p?.isFinished == true,
            )
        }
        val shown = when (filter) {
            EpisodeFilter.ALL -> rows
            EpisodeFilter.UNPLAYED -> rows.filter { !it.finished }
            EpisodeFilter.DOWNLOADED -> rows.filter { it.key in d.complete }
        }
        return PodcastUiState(
            found = true,
            itemId = itemId,
            title = item.title,
            author = item.author?.takeIf { it.isNotBlank() },
            description = plainDescription(item.description),
            coverPath = covers.pathFor(sid, itemId),
            coverVersion = item.coverUpdatedAt,
            episodeCount = maxOf(item.numEpisodes, d.episodes.size),
            episodes = shown,
            filter = filter,
            incomplete = !item.detailsLoaded || d.episodes.size < item.numEpisodes,
            loading = loading,
        )
    }

    private var descSource: String? = null
    private var descPlain: String? = null

    /** HTML stripped to plain text; cached by source string so progress ticks do not re-parse. */
    private fun plainDescription(html: String?): String? {
        if (html == null) return null
        if (html != descSource) {
            descPlain = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace("￼", "").trim().takeIf { it.isNotEmpty() }
            descSource = html
        }
        return descPlain
    }

    fun setFilter(f: EpisodeFilter) {
        filter.value = f
    }

    fun play(key: String) {
        serverId?.let { player.play(it, key) }
    }
}
