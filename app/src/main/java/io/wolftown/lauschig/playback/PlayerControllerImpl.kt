package io.wolftown.lauschig.playback

import io.wolftown.lauschig.R
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.ProgressSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** App-singleton; talks to the playback service through a [PlayerFacade]. Contains all play/seek/state logic. */
@Singleton
class PlayerControllerImpl(
    private val scope: CoroutineScope,
    private val facade: PlayerFacade,
    private val factory: MediaItemFactory,
    private val loadItem: suspend (serverId: String, itemId: String) -> ItemWithDetails?,
    /** Waits briefly for an online base URL; only called when the item is not fully local. */
    private val onlineBaseUrl: suspend () -> HttpUrl?,
    private val coverFile: (serverId: String, itemId: String) -> File?,
    private val pollMs: Long = 1_000,
    /** File-system checks (local files, cover) run here, never on the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlayerController {

    @Inject constructor(
        facade: MediaControllerFacade,
        factory: MediaItemFactory,
        itemDao: ItemDao,
        api: ApiProvider,
        files: DownloadFiles,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        facade = facade,
        factory = factory,
        loadItem = { serverId, itemId -> itemDao.observeItem(serverId, itemId).first() },
        onlineBaseUrl = {
            withTimeoutOrNull(3_000) { api.connection.filterIsInstance<ConnectionState.Online>().first() }?.baseUrl
        },
        coverFile = { serverId, itemId -> try { files.coverFile(serverId, itemId) } catch (e: Exception) { null } },
    )

    private class Current(val item: ItemWithDetails, val mapper: TimelineMapper, val coverPath: String?)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var current: Current? = null
    /** The last loaded title; survives a service stop so play/pause can start it again. */
    private var last: Pair<String, String>? = null
    private var playJob: Job? = null
    private var pollJob: Job? = null

    init {
        facade.setOnChange { refresh() }
        facade.setOnError { _state.update { it.copy(isPlaying = false, error = R.string.error_playback_failed) } }
        facade.setOnDisconnected {
            // The service is gone; forget the loaded item so the next play() rebuilds the queue.
            playJob?.cancel(); pollJob?.cancel(); pollJob = null
            current = null
            _state.update { it.copy(isPlaying = false) }
        }
    }

    override fun play(serverId: String, itemId: String, startSec: Double?) {
        playJob?.cancel()
        playJob = scope.launch {
            val cur = current
            if (cur != null && cur.item.item.id == itemId && cur.item.item.serverId == serverId && startSec == null && facade.hasMedia()) {
                resumeLoaded(cur); return@launch
            }
            val item = loadItem(serverId, itemId) ?: return@launch
            val allLocal = withContext(io) { factory.isFullyLocal(item) }
            val base = if (allLocal) null else onlineBaseUrl()
            val items = try {
                withContext(io) { factory.build(item, base) }
            } catch (e: PlaybackUnavailableException) {
                _state.update { it.copy(error = R.string.error_not_downloaded_offline) }
                return@launch
            }
            val mapper = TimelineMapper(item.audioFiles)
            val start = startSec ?: item.progress?.takeUnless { it.isFinished }?.currentTimeSec ?: 0.0
            val (index, offsetMs) = mapper.toFile(start)
            val coverPath = withContext(io) { coverFile(serverId, itemId)?.takeIf { it.isFile }?.path }
            current = Current(item, mapper, coverPath)
            last = serverId to itemId
            _state.value = PlayerUiState(itemId = itemId, title = item.item.title)
            facade.setMediaItems(items, index, offsetMs)
            facade.play()
            refresh()
        }
    }

    /**
     * Resumes the loaded, paused title. If sync merged a newer position meanwhile (another device; differs by more
     * than the jump threshold), playback continues there instead of overwriting it with the stale player position.
     */
    private suspend fun resumeLoaded(cur: Current) {
        val saved = loadItem(cur.item.item.serverId, cur.item.item.id)?.progress?.takeUnless { it.isFinished }
        if (saved != null && !facade.snapshot().isPlaying) {
            val snap = facade.snapshot()
            val playerSec = cur.mapper.toGlobal(snap.mediaIndex, snap.positionMs)
            if (abs(saved.currentTimeSec - playerSec) > ProgressSync.JUMP_THRESHOLD_SEC) {
                val (i, ms) = cur.mapper.toFile(saved.currentTimeSec)
                facade.seekTo(i, ms)
            }
        }
        facade.play()
        refresh()
    }

    override fun togglePlayPause() {
        val c = current
        if (c == null) {
            // The service was stopped (e.g. app swiped away while paused): start the last title again.
            last?.let { (serverId, itemId) -> play(serverId, itemId) }
            return
        }
        if (facade.snapshot().isPlaying) {
            facade.pause(); refresh()
        } else {
            playJob?.cancel()
            playJob = scope.launch { resumeLoaded(c) }
        }
    }

    override fun seekBy(deltaSec: Int) {
        val c = current ?: return
        val snap = facade.snapshot()
        seekGlobal(c, c.mapper.toGlobal(snap.mediaIndex, snap.positionMs) + deltaSec)
    }

    override fun seekTo(globalSec: Double) {
        val c = current ?: return
        seekGlobal(c, globalSec)
    }

    override fun seekToChapter(index: Int) {
        val c = current ?: return
        val chapter = c.item.chapters.getOrNull(index) ?: return
        seekGlobal(c, chapter.startSec)
    }

    override fun setSpeed(speed: Float) {
        facade.setSpeed(speed)
        refresh()
    }

    private fun seekGlobal(c: Current, globalSec: Double) {
        val (i, ms) = c.mapper.toFile(globalSec)
        facade.seekTo(i, ms)
        refresh()
    }

    private fun refresh() {
        val c = current ?: return
        val snap = facade.snapshot()
        val pos = c.mapper.toGlobal(snap.mediaIndex, snap.positionMs)
        val duration = c.mapper.totalSec.takeIf { it > 0 } ?: c.item.item.durationSec
        val chapters = c.item.chapters
        val ci = c.mapper.chapterAt(pos, chapters)
        val chapter = chapters.getOrNull(ci)
        _state.update {
            it.copy(
                itemId = c.item.item.id,
                title = c.item.item.title,
                coverPath = c.coverPath,
                isPlaying = snap.isPlaying,
                positionSec = pos,
                durationSec = duration,
                chapterIndex = ci,
                chapterTitle = chapter?.title,
                chapterStartSec = chapter?.startSec ?: 0.0,
                chapterEndSec = chapter?.endSec ?: duration,
                speed = snap.speed,
                error = if (snap.isPlaying) null else it.error,
            )
        }
        if (snap.isPlaying) startPolling() else { pollJob?.cancel(); pollJob = null }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { while (true) { delay(pollMs); refresh() } }
    }
}
