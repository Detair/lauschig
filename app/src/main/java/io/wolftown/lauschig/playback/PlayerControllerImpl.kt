package io.wolftown.lauschig.playback

import android.util.Log
import io.wolftown.lauschig.R
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.awaitOnlineAnswer
import androidx.media3.common.MediaItem
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.ProgressSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /** The online base URL, after the first connection check if it is still running; only called for streams. */
    private val onlineBaseUrl: suspend () -> HttpUrl?,
    private val coverFile: (serverId: String, itemId: String) -> File?,
    /** Fetches chapters and audio files from the server; used when a title is played before its details were loaded. */
    private val loadDetails: suspend (serverId: String, itemId: String) -> Unit = { _, _ -> },
    private val pollMs: Long = 1_000,
    /** File-system checks (local files, cover) run here, never on the main thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Number of downloaded files of a title; every change re-picks the source of each file (see [syncSources]). */
    private val localCount: (serverId: String, itemId: String) -> Flow<Int> = { _, _ -> emptyFlow() },
    /** Called once a title started that streams at least one file (not on resume); see [PlayDownloadStarter]. */
    private val onStreamStarted: (serverId: String, itemId: String) -> Unit = { _, _ -> },
    /** The service's stream; shown as [PlayerUiState.streamLoad] while a streamed title loads. */
    private val streamStats: Flow<StreamStats> = emptyFlow(),
    private val downloadThrottled: Flow<Boolean> = emptyFlow(),
    /**
     * Ms to jump back when a title is loaded at its saved position after a pause of the given length (the service
     * may have been stopped meanwhile, so its own resume rewind cannot see that pause); see [rewindMs].
     */
    private val rewindAfterPause: suspend (pauseMs: Long) -> Long = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The online server's base URL as it changes (null while offline); a streamed queue follows it. */
    private val onlineBase: Flow<HttpUrl?> = emptyFlow(),
    /** The seek steps of the settings (back, forward seconds); shown as [PlayerUiState.seekBackSec] / `seekForwardSec`. */
    private val seekSteps: Flow<Pair<Int, Int>> = emptyFlow(),
) : PlayerController {

    @Inject constructor(
        facade: MediaControllerFacade,
        factory: MediaItemFactory,
        itemDao: ItemDao,
        episodeDao: EpisodeDao,
        audioFileDao: AudioFileDao,
        api: ApiProvider,
        files: DownloadFiles,
        librarySync: LibrarySync,
        starter: PlayDownloadStarter,
        monitor: StreamMonitor,
        playbackSettings: PlaybackSettings,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        facade = facade,
        factory = factory,
        loadItem = { serverId, itemId -> loadPlayable(itemDao, episodeDao, serverId, itemId) },
        // The first connection check of a cold start may take a while (unreachable home address first): wait for its answer.
        onlineBaseUrl = {
            (api.connectionState.value as? ConnectionState.Online)?.baseUrl
                ?: awaitOnlineAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS)?.baseUrl
        },
        coverFile = { serverId, itemId -> try { files.playableCover(serverId, itemId) } catch (e: Exception) { null } },
        // An episode's details are its podcast's: one expanded-item fetch stores the episodes with their files and chapters.
        loadDetails = librarySync::loadDetailsForPlayable,
        localCount = audioFileDao::observeLocalCount,
        onStreamStarted = starter::start,
        streamStats = monitor.state,
        downloadThrottled = monitor.downloadThrottled,
        rewindAfterPause = { pause -> rewindMs(pause, playbackSettings.rewindOnResumeSec.first()) },
        onlineBase = api.connection.map { (it as? ConnectionState.Online)?.baseUrl },
        seekSteps = combine(playbackSettings.seekBackSec, playbackSettings.seekForwardSec, ::Pair),
    )

    /** [base] is the server the streamed files of [items] point at (null when all are local). */
    private class Current(
        val item: ItemWithDetails,
        val mapper: TimelineMapper,
        val coverPath: String?,
        val streaming: Boolean,
        val base: HttpUrl?,
        val items: List<MediaItem>,
    ) {
        val chapterEnds: List<Double> = item.chapters.map { it.endSec }
    }

    /** The scope's dispatcher (main in the app): [stop] runs there like every other call. */
    private val mainContext = scope.coroutineContext.minusKey(Job)

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Adopts a title the service started by itself (next in the queue); see [adopt]. */
    private var adoptJob: Job? = null
    private var adopting: String? = null

    private var current: Current? = null
    /** The last loaded title; survives a service stop so play/pause can start it again. */
    private var last: Pair<String, String>? = null
    private var playJob: Job? = null
    /** From [play] until the player reports playing (or the start failed). */
    private var starting = false
    private var pollJob: Job? = null
    /** Watches the downloads of the loaded title; see [syncSources]. */
    private var localJob: Job? = null
    /** Follows the online server's address for a streamed queue; see [onOnlineBase]. */
    private var baseJob: Job? = null
    /** The address changed while playing: move the queue when it stops playing (network error, pause), not mid-buffer. */
    private var pendingBase: HttpUrl? = null
    /** The player played at the last [refresh]; its stop applies a [pendingBase]. */
    private var wasPlaying = false
    private var load: StreamLoad? = null
    private var throttled = false
    private var seekBack = PlaybackSettings.DEFAULT_SEEK_BACK
    private var seekForward = PlaybackSettings.DEFAULT_SEEK_FORWARD

    /** A fresh state for a new or no title; the settings it shows are kept. */
    private fun freshState(itemId: String? = null, title: String = "", isStreaming: Boolean = false, isLoading: Boolean = false) =
        PlayerUiState(
            itemId = itemId, title = title, isStreaming = isStreaming, isLoading = isLoading, downloadThrottled = throttled,
            seekBackSec = seekBack, seekForwardSec = seekForward,
        )

    init {
        facade.setOnChange { refresh() }
        facade.setOnError { code ->
            starting = false
            _state.update { it.copy(isPlaying = false, isLoading = false, error = R.string.error_playback_failed) }
            // A stream that ran dry at an old address (home LAN, now in the car): move to the new one. It plays on only
            // because ExoPlayer still wants to (playWhenReady survives the error); nothing here ever starts playback.
            if (code in IO_ERRORS && facade.streamBase() != null) applyPendingBase()
        }
        facade.setOnDisconnected {
            // The service is gone; forget the loaded item so the next play() rebuilds the queue.
            playJob?.cancel(); pollJob?.cancel(); pollJob = null
            localJob?.cancel(); localJob = null
            baseJob?.cancel(); baseJob = null
            current = null
            starting = false
            _state.update { it.copy(isPlaying = false, isLoading = false, streamLoad = null) }
        }
        // Only what the UI shows: a playing stream's stats change constantly but map to null.
        scope.launch { streamStats.map { it.toLoad() }.distinctUntilChanged().collect { load = it; if (current != null) refresh() } }
        scope.launch { downloadThrottled.collect { t -> throttled = t; _state.update { it.copy(downloadThrottled = t) } } }
        scope.launch {
            seekSteps.distinctUntilChanged().collect { (back, forward) ->
                seekBack = back; seekForward = forward
                _state.update { it.copy(seekBackSec = back, seekForwardSec = forward) }
            }
        }
    }

    override fun play(serverId: String, itemId: String, startSec: Double?) {
        playJob?.cancel()
        playJob = scope.launch {
            val cur = current
            if (cur != null && cur.item.item.id == itemId && cur.item.item.serverId == serverId && startSec == null && facade.hasMedia()) {
                resumeLoaded(cur); return@launch
            }
            var item = loadItem(serverId, itemId) ?: return@launch
            // Feedback right away: details, connection and buffering can take a few seconds when streaming.
            val previous = current
            if (previous != null) {
                current = null
                localJob?.cancel(); localJob = null
                baseJob?.cancel(); baseJob = null
                pollJob?.cancel(); pollJob = null
                if (facade.snapshot().isPlaying) facade.pause()
            }
            starting = true
            last = serverId to itemId
            _state.value = freshState(itemId, item.item.title, isLoading = true)
            /** Start failed: the previous title (if any) stays loaded, the error is shown. */
            fun fail(error: Int) {
                starting = false
                current = previous
                if (previous == null) _state.update { it.copy(isLoading = false, error = error) }
                else { refresh(); watchDownloads(previous); _state.update { it.copy(error = error) } }
            }
            // A device title never touches a server: no base URL, no details, no download on play.
            val deviceTitle = DeviceLibrary.isLocal(serverId, itemId)
            val allLocal = withContext(io) { factory.isFullyLocal(item) }
            if (deviceTitle && !allLocal) {
                fail(R.string.error_local_unavailable)
                return@launch
            }
            val base = if (allLocal) null else onlineBaseUrl()
            if (item.audioFiles.isEmpty()) {
                // The library list carries no audio files; they arrive with the details, which may not be loaded yet.
                if (base == null) {
                    fail(R.string.error_not_downloaded_offline)
                    return@launch
                }
                item = try {
                    loadDetails(serverId, itemId)
                    loadItem(serverId, itemId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "loading details for playback failed", e)
                    null
                }?.takeIf { it.audioFiles.isNotEmpty() } ?: run {
                    fail(R.string.error_item_not_playable)
                    return@launch
                }
            }
            val items = try {
                withContext(io) { factory.build(item, base) }
            } catch (e: PlaybackUnavailableException) {
                // A device title can only get here if its documents vanished since the check above.
                fail(if (deviceTitle) R.string.error_local_unavailable else R.string.error_not_downloaded_offline)
                return@launch
            }
            val mapper = TimelineMapper(item.audioFiles)
            val start = startSec ?: item.progress?.takeUnless { it.isFinished }?.let { p ->
                if (p.currentTimeSec <= 0) 0.0
                else (p.currentTimeSec - rewindAfterPause(clock() - p.lastUpdate) / 1000.0).coerceAtLeast(0.0)
            } ?: 0.0
            val (index, offsetMs) = mapper.toFile(start)
            val coverPath = withContext(io) { coverFile(serverId, itemId)?.takeIf { it.isFile }?.path }
            val started = Current(item, mapper, coverPath, streaming = !allLocal, base, items)
            current = started
            _state.value = freshState(itemId, item.item.title, isStreaming = !allLocal, isLoading = true)
            facade.setMediaItems(items, index, offsetMs)
            facade.play()
            refresh()
            watchDownloads(started)
            if (!allLocal && !deviceTitle) onStreamStarted(serverId, itemId)
        }
    }

    /**
     * While [c] is loaded, a finished or deleted download of one of its files changes that file's source, and a change
     * of the server's address moves its streamed files ([onOnlineBase]).
     */
    private fun watchDownloads(c: Current) {
        localJob?.cancel(); localJob = null
        baseJob?.cancel(); baseJob = null
        pendingBase = null
        val key = c.item.item
        localJob = scope.launch {
            localCount(key.serverId, key.id).distinctUntilChanged().collect { syncSources() }
        }
        watchBase(c)
    }

    /** Follows the server's address for [c] (also when its files could not be rebuilt yet, e.g. adopted offline). */
    private fun watchBase(c: Current) {
        baseJob?.cancel(); baseJob = null
        val key = c.item.item
        if (!DeviceLibrary.isLocal(key.serverId, key.id)) baseJob = scope.launch {
            onlineBase.filterNotNull().distinctUntilChanged().collect { onOnlineBase(it) }
        }
    }

    /**
     * The online server's address is [base] now (e.g. the home LAN address gave way to the public one in the car). A
     * queue that streams from another address moves there: right away while it does not play (current file reloaded at
     * its position, the others swapped in place); while it plays it keeps playing from its buffer and moves as soon as
     * it stops – a network error or a pause ([pendingBase]) – so coming home causes no hiccup. Moving never changes
     * whether it plays (playWhenReady is kept). Local files never move.
     */
    private suspend fun onOnlineBase(base: HttpUrl) {
        val c = current ?: return
        val actual = facade.streamBase()?.toHttpUrlOrNull() ?: return
        if (actual == base) {
            pendingBase = null
            return
        }
        if (facade.snapshot().isPlaying) {
            pendingBase = base
            return
        }
        rebase(c, base)
    }

    /** The player stopped playing (network error, pause): an address that changed meanwhile applies now. */
    private fun applyPendingBase() {
        val base = pendingBase ?: return
        val c = current ?: return
        pendingBase = null
        scope.launch { rebase(c, base) }
    }

    private suspend fun rebase(c: Current, base: HttpUrl) {
        pendingBase = null
        Log.i(TAG, "moving the streamed queue to $base")
        syncSources(newBase = base, force = true)
        refresh()
    }

    /**
     * Rebuilds the queue of the current title from the database and swaps every file whose source changed (download
     * finished: stream -> local; download deleted: local -> stream): queued files in place (no gap), the playing file
     * by reloading the queue at the current position (a short re-buffer). A changed file layout (server rescan) is
     * left alone until the next start. A deleted download while offline pauses with the not-downloaded error.
     */
    private suspend fun syncSources(newBase: HttpUrl? = null, force: Boolean = false): Boolean {
        val c = current ?: return false
        val fresh = loadItem(c.item.item.serverId, c.item.item.id) ?: return false
        if (current !== c || fresh.audioFiles.map { it.index } != c.item.audioFiles.map { it.index }) return false
        val allLocal = withContext(io) { factory.isFullyLocal(fresh) }
        val base = if (allLocal || DeviceLibrary.isLocal(fresh.item.serverId, fresh.item.id)) null else newBase ?: c.base ?: onlineBaseUrl()
        val items = try {
            withContext(io) { factory.build(fresh, base) }
        } catch (e: PlaybackUnavailableException) {
            if (current === c && facade.hasMedia()) {
                facade.pause(); refresh()
                val error = if (DeviceLibrary.isLocal(fresh.item.serverId, fresh.item.id)) R.string.error_local_unavailable else R.string.error_not_downloaded_offline
                _state.update { it.copy(error = error) }
            }
            return false
        }
        if (current !== c || !facade.hasMedia()) return false
        // [force]: the player's streamed files may point elsewhere than the app's copy (a title the service started).
        val changed = items.indices.filter {
            items[it].localConfiguration?.uri != c.items.getOrNull(it)?.localConfiguration?.uri || (force && isStream(items[it]))
        }
        if (changed.isEmpty()) return false
        current = Current(fresh, c.mapper, c.coverPath, streaming = !allLocal, base, items)
        val snap = facade.snapshot()
        if (snap.mediaIndex in changed) {
            Log.i(TAG, "switching the playing file to ${items[snap.mediaIndex].localConfiguration?.uri?.scheme}")
            facade.setMediaItems(items, snap.mediaIndex, snap.positionMs)
        } else {
            changed.forEach { facade.replaceMediaItem(it, items[it]) }
        }
        refresh()
        return true
    }

    private fun isStream(item: MediaItem) = item.localConfiguration?.uri?.scheme.let { it == "http" || it == "https" }

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
        if (c == null && starting) {
            // Tapped while the title is still being prepared: cancel the start.
            playJob?.cancel()
            starting = false
            _state.update { it.copy(isLoading = false) }
            return
        }
        if (c == null) {
            // The service was stopped (e.g. app swiped away while paused): start the last title again.
            last?.let { (serverId, itemId) -> play(serverId, itemId) }
            return
        }
        if (facade.snapshot().isPlaying || starting) {
            starting = false
            facade.pause(); refresh()
        } else {
            playJob?.cancel()
            playJob = scope.launch { resumeLoaded(c) }
        }
    }

    override fun pause() {
        if (current == null && starting) {
            playJob?.cancel()
            starting = false
            _state.update { it.copy(isLoading = false) }
            return
        }
        playJob?.cancel()
        starting = false
        facade.pause()
        refresh()
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

    override fun seekToAndPlay(globalSec: Double) {
        val c = current ?: return
        playJob?.cancel()
        seekGlobal(c, globalSec)
        facade.play()
        refresh()
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

    override fun setVolume(volume: Float) = facade.setVolume(volume)

    override fun refreshNow() = refresh()

    /**
     * The service started a title by itself (Android Auto, voice, playback resumption) and the app may not even be
     * connected: connect and show it. Idempotent; a title the app started itself is left alone.
     */
    override fun adoptFromService() {
        scope.launch {
            try {
                facade.connect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connecting to the playback service failed", e)
                return@launch
            }
            refresh()
        }
    }

    override suspend fun stop() = withContext(mainContext) {
        playJob?.cancel(); playJob = null
        adoptJob?.cancel(); adoptJob = null; adopting = null
        // Paused first and given a moment: the service's tracker saves the position before the queue is emptied.
        if (facade.snapshot().isPlaying) {
            facade.pause()
            delay(STOP_SETTLE_MS)
        }
        pollJob?.cancel(); pollJob = null
        localJob?.cancel(); localJob = null
        baseJob?.cancel(); baseJob = null
        current = null
        last = null
        starting = false
        pendingBase = null
        wasPlaying = false
        facade.stop()
        _state.value = freshState()
    }

    private fun seekGlobal(c: Current, globalSec: Double) {
        val (i, ms) = c.mapper.toFile(globalSec)
        facade.seekTo(i, ms)
        refresh()
    }

    private fun refresh() {
        val c = current
        if (c == null) {
            wasPlaying = false
            // Nothing loaded here, but the service may be playing a title it started itself (car, resumption).
            if (!starting) facade.currentKey()?.let { (serverId, itemId) -> adopt(serverId, itemId) }
            return
        }
        val snap = facade.snapshot()
        val stopped = wasPlaying && !snap.isPlaying
        wasPlaying = snap.isPlaying
        if (stopped) applyPendingBase()
        if (snap.isPlaying) starting = false
        val playing = facade.currentKey()
        if (playing != null && !starting && playing.second != c.item.item.id) {
            adopt(playing.first, playing.second)
            return
        }
        val pos = c.mapper.toGlobal(snap.mediaIndex, snap.positionMs)
        val duration = c.mapper.totalSec.takeIf { it > 0 } ?: c.item.item.durationSec
        val chapters = c.item.chapters
        val ci = c.mapper.chapterAt(pos, chapters)
        val chapter = chapters.getOrNull(ci)
        val loading = (starting && !snap.isPlaying) || snap.isBuffering
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
                chapterEndsSec = c.chapterEnds,
                speed = snap.speed,
                isStreaming = c.streaming,
                isLoading = loading,
                streamLoad = if (loading && c.streaming) load else null,
                error = if (snap.isPlaying) null else it.error,
            )
        }
        if (snap.isPlaying || starting) startPolling() else { pollJob?.cancel(); pollJob = null }
    }

    /**
     * The service switched to another title on its own (the next queued one when a title ended, a title started from
     * the car or by playback resumption): load that title as the current one, without touching the player's queue.
     */
    private fun adopt(serverId: String, itemId: String) {
        if (adoptJob?.isActive == true && adopting == itemId) return
        adopting = itemId
        adoptJob = scope.launch {
            val item = loadItem(serverId, itemId) ?: return@launch
            if (facade.currentKey()?.second != itemId) return@launch
            localJob?.cancel(); localJob = null
            baseJob?.cancel(); baseJob = null
            val allLocal = withContext(io) { factory.isFullyLocal(item) }
            val base = if (allLocal || DeviceLibrary.isLocal(serverId, itemId)) null else onlineBaseUrl()
            val items = try {
                withContext(io) { factory.build(item, base) }
            } catch (e: PlaybackUnavailableException) {
                emptyList()
            }
            val coverPath = withContext(io) { coverFile(serverId, itemId)?.takeIf { it.isFile }?.path }
            if (facade.currentKey()?.second != itemId) return@launch
            last = serverId to itemId
            val adopted = Current(item, TimelineMapper(item.audioFiles), coverPath, streaming = !allLocal, base, items)
            current = adopted
            _state.value = freshState(itemId, item.item.title, isStreaming = !allLocal)
            refresh()
            if (items.isNotEmpty()) watchDownloads(adopted) else watchBase(adopted)
        }
    }

    /** Null unless the stream is actually loading (a local file of a partly downloaded title can buffer too). */
    private fun StreamStats.toLoad(): StreamLoad? {
        if (!active || (phase != StreamPhase.STARTING && phase != StreamPhase.STALLED)) return null
        val eta = expectedBytes?.takeIf { bytesPerSec > 0 }?.let { ((it - bytes).coerceAtLeast(0) + bytesPerSec - 1) / bytesPerSec }
        return StreamLoad(bytes, expectedBytes, bytesPerSec, eta)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { while (true) { delay(pollMs); refresh() } }
    }

    private companion object {
        const val TAG = "PlayerController"
        /** How long [stop] waits after pausing a playing title before it empties the queue. */
        const val STOP_SETTLE_MS = 300L
        /** `PlaybackException.ERROR_CODE_IO_*`: the source could not be read (network gone, address unreachable, 4xx/5xx). */
        val IO_ERRORS = 2000..2999
    }
}
