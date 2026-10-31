package io.wolftown.lauschig.playback

import io.wolftown.lauschig.R
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.ApiProvider
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
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

    private class Current(val item: ItemWithDetails, val mapper: TimelineMapper)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var current: Current? = null
    private var playJob: Job? = null
    private var pollJob: Job? = null

    init { facade.setOnChange { refresh() } }

    override fun play(serverId: String, itemId: String, startSec: Double?) {
        playJob?.cancel()
        playJob = scope.launch {
            val cur = current
            if (cur != null && cur.item.item.id == itemId && cur.item.item.serverId == serverId && startSec == null) {
                facade.play(); refresh(); return@launch
            }
            val item = loadItem(serverId, itemId) ?: return@launch
            val allLocal = item.audioFiles.isNotEmpty() && item.audioFiles.all { it.localPath != null }
            val items = try {
                factory.build(item, if (allLocal) null else onlineBaseUrl())
            } catch (e: PlaybackUnavailableException) {
                _state.update { it.copy(error = R.string.error_not_downloaded_offline) }
                return@launch
            }
            val mapper = TimelineMapper(item.audioFiles)
            val start = startSec ?: item.progress?.takeUnless { it.isFinished }?.currentTimeSec ?: 0.0
            val (index, offsetMs) = mapper.toFile(start)
            current = Current(item, mapper)
            _state.value = PlayerUiState(itemId = itemId, title = item.item.title)
            facade.setMediaItems(items, index, offsetMs)
            facade.play()
            refresh()
        }
    }

    override fun togglePlayPause() {
        if (current == null) return
        if (facade.snapshot().isPlaying) facade.pause() else facade.play()
        refresh()
    }

    override fun seekBy(deltaSec: Int) {
        val c = current ?: return
        val snap = facade.snapshot()
        seekGlobal(c, c.mapper.toGlobal(snap.mediaIndex, snap.positionMs) + deltaSec)
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
                coverPath = coverFile(c.item.item.serverId, c.item.item.id)?.takeIf { f -> f.isFile }?.path,
                isPlaying = snap.isPlaying,
                positionSec = pos,
                durationSec = duration,
                chapterIndex = ci,
                chapterTitle = chapter?.title,
                chapterStartSec = chapter?.startSec ?: 0.0,
                chapterEndSec = chapter?.endSec ?: duration,
                speed = snap.speed,
                error = null,
            )
        }
        if (snap.isPlaying) startPolling() else { pollJob?.cancel(); pollJob = null }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { while (true) { delay(pollMs); refresh() } }
    }
}
