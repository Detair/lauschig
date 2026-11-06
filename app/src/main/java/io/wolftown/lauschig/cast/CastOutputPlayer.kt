package io.wolftown.lauschig.cast

import android.os.Handler
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.wolftown.lauschig.R
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.MediaItemFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import kotlin.math.abs

/**
 * The player of a device with Play Services: plays on this device or on a Cast receiver, and everything built on it (session,
 * notification, progress, queue, chapters, widget, play-key guard) does not have to know which. It forwards to [device] until
 * a person opens the full player, then to Media3's `CastPlayer` ([attach]), which holds the device and the receiver.
 *
 * While on the device it only forwards. While on a receiver it stands between the app and `RemoteCastPlayer`, which is a thin
 * view of the receiver's status and would otherwise leak into the rest of the app:
 *
 * - **Only session addresses reach a receiver.** The app sets the device's items ([MediaItemFactory]); here a server session
 *   is opened and [ReceiverItems] are sent. The device's items wait for the way back, and stay up to date.
 * - **The title stays in the player unless the receiver's state is proven** ([getState]). `RemoteCastPlayer` is empty for seconds
 *   after a load, its index only moves with a status and its position only with a progress report, so a status may pair the
 *   wrong file with the wrong position. What reaches the progress is the title's own position until the receiver proves otherwise:
 *   the start file at a plausible position after a load ([startProven]), a sane position after a file boundary ([remember]).
 *   Without a proof it asks for a fresh status, and a load gives up at [loadLimitMs].
 * - **An end is an end, only at the end.** The receiver's idle state with reason "finished" becomes [Player.STATE_ENDED], which the
 *   progress and the queue are waiting for, only near the true end of the last file ([atTheEnd]). Anywhere else it is a file
 *   boundary or a doubt: nothing ends, and a doubt that lasts [DOUBT_MS] leaves the title paused, with the play button loading it.
 * - **A load failure ends the cast**, with the reason said plainly; paused here, position kept.
 * - **A suspended session leaves the receiver alone.** The phone's Wi-Fi drops, Media3 moves to the device, and the receiver plays
 *   on. The player then shows neither playing nor paused (buffering, on the receiver), makes no sound and sends nothing: nothing
 *   here can reach the TV. On resume it finds out what the receiver does, and takes that over without any load.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class CastOutputPlayer(
    private val device: Player,
    private val link: ReceiverLink,
    private val sessions: CastSessionSource,
    private val scope: CoroutineScope,
    private val say: (Int) -> Unit,
    /** A car is connected: playback stays on this device. */
    private val carConnected: () -> Boolean,
    /** What a person waits at most for a load, over all addresses. */
    private val loadLimitMs: Long = LOAD_LIMIT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** What the line under the title should say. */
    private val publish: (CastActivity) -> Unit = {},
) : ForwardingSimpleBasePlayer(device), CastPlayer.TransferCallback, ReceiverListener {

    /** The title of a cast: what it should be, whatever the receiver says at the moment. */
    private class CastTitle(val serverId: String, val key: String, var local: List<MediaItem>, var index: Int, var positionMs: Long, var playWhenReady: Boolean, var speed: Float) {
        /** It plays on here once back on the device: only if a person took it back in the system's output switcher, or it never left. */
        var resumeOnDevice = false

        fun owns(item: MediaItem?) = item?.titleKey() == (serverId to key)
    }

    private enum class Phase {
        /** Nothing on a receiver. */
        NONE,

        /** The session is opening or the receiver is loading. Shown as buffering at the title's position. */
        LOADING,

        /** Back from a suspend: what the receiver does is not known yet. Shown as buffering, nothing is sent. */
        RESUMING,

        /** The receiver plays or holds the title: its own state is the player's, as far as it is proven. */
        ACTIVE,

        /** The receiver played the queue to its end. */
        ENDED,

        /** The receiver holds nothing of ours: shown paused, the play button loads it again. Its own title playing leads back. */
        STOPPED,

        /** The connection is lost for now and the receiver may play on. Media3 is on the device, which stays silent. */
        SUSPENDED,

        /** The Cast session is ending; the title goes back to the device. */
        LEAVING,
    }

    private var attached: Player? = null
    private var cast: CastTitle? = null
    private var phase = Phase.NONE
        set(value) {
            field = value
            publish(
                when (value) {
                    Phase.LOADING, Phase.RESUMING, Phase.ACTIVE -> CastActivity.PLAYING
                    Phase.ENDED, Phase.STOPPED -> CastActivity.CONNECTED
                    Phase.SUSPENDED -> CastActivity.SUSPENDED
                    Phase.NONE, Phase.LEAVING -> CastActivity.NONE
                },
            )
        }

    /** The title that went to the device with the Cast session not over yet: a suspend may come back for it. */
    private var kept: CastTitle? = null
    /** The session of the title on the receiver. Closed when the cast ends or anything is loaded anew. */
    private var session: OpenCastSession? = null
    /** Bumped by every new load and every end: a session that opens too late is closed again. */
    private var generation = 0
    /** One open at a time: the server closes a device's other sessions when it opens one, so the last one opened must be the last wanted. */
    private val opening = Mutex()
    private var loadSent = false
    /** What the load in flight, or the resume, starts from: the proof compares with it. */
    private var sentIndex = 0
    private var sentPositionMs = 0L
    private var sentAt = 0L
    /** Where the receiver can reach the server, and how far down the list this load is. */
    private var bases: List<HttpUrl> = emptyList()
    private var baseIndex = 0
    /** The receiver's failure was answered: the status keeps saying so until the next load has an effect. */
    private var failureAnswered = false
    private var pendingSeek = false
    /** A person pressed play or pause while the resume could not tell the receiver. */
    private var pressedWhileResuming = false
    private var receiverError: Int? = null
    private var statusAsked = false
    /** Since when the receiver's state makes no sense for a title it plays; see [doubt]. */
    private var doubtSince: Long? = null
    /** When the receiver last crossed a file boundary by itself; its position is the old file's until a report says otherwise. */
    private var boundaryAt: Long? = null
    private var watchdog: Job? = null
    private var ticker: Job? = null
    /** When a playing cast came back to this device: the output switcher's "this phone" may follow it (see [onCastTakenBack]). */
    private var playingUntil: Long? = null
    private val handler = Handler(device.applicationLooper)

    /** A person's speed for the title in the player, while on a receiver (which clamps it and reports its own). */
    var onSpeedRequested: (Float) -> Unit = {}

    /** The receiver took the title, or the title came back: the volume the sleep timer wants applies there now. */
    var onOutputChanged: () -> Unit = {}

    /** Puts the Cast player on top of the device. It starts on the device; nothing changes for what plays. */
    fun attach(castPlayer: Player) {
        attached = castPlayer
        setPlayer(castPlayer)
        castPlayer.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = evaluate()
            },
        )
    }

    private val onReceiver: Boolean get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    /** The receiver's business, not the device's: also while the session is suspended and Media3 sits on the device. */
    private val casting: Boolean get() = onReceiver || phase == Phase.SUSPENDED

    // ---- The way there and back: called by Media3's CastPlayer right before it changes the active player.

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (sourcePlayer === device) toReceiver() else toDevice()
    }

    private fun toReceiver() {
        receiverError = null
        val back = kept?.takeIf { it.owns(device.currentMediaItem) }
        kept = null
        if (back != null) {
            resume(back)
            return
        }
        val key = device.currentMediaItem?.titleKey()
        if (key == null) {
            cast = null
            phase = Phase.NONE
            publish(CastActivity.CONNECTED)
            return
        }
        val c = CastTitle(
            key.first, key.second, List(device.mediaItemCount, device::getMediaItemAt), device.currentMediaItemIndex,
            device.currentPosition.coerceAtLeast(0), device.playWhenReady && device.playbackState != Player.STATE_ENDED, device.playbackParameters.speed,
        )
        cast = c
        // No local focus and no local sound while casting; Media3 stops the device right after this.
        device.playWhenReady = false
        when {
            // Only the system's output switcher gets here (the player's button explains first): it waits here, and the player says why.
            DeviceLibrary.isLocal(c.serverId, c.key) -> failed(R.string.cast_refused_device_title)
            // A car stays as it was: the sound goes on where it is, and the button is not even there.
            carConnected() -> {
                c.resumeOnDevice = c.playWhenReady
                leave()
            }
            else -> load(c)
        }
    }

    /**
     * The position is the one the player remembered, never `source.currentPosition`: `RemoteCastPlayer` answers with its last
     * report there, which survives a session and may be a previous cast's.
     */
    private fun toDevice() {
        generation++
        stopJobs()
        val c = cast
        val was = phase
        cast = null
        phase = Phase.NONE
        loadSent = false
        pendingSeek = false
        if (c == null) return
        playingUntil = if (was == Phase.ACTIVE && c.playWhenReady) clock() + TAKE_BACK_WINDOW_MS else null
        putOnDevice(c)
        kept = c
        if (link.isSuspended()) suspended(c)
        handler.post { onOutputChanged() }
    }

    private fun putOnDevice(c: CastTitle) {
        device.setPlaybackParameters(PlaybackParameters(c.speed))
        device.setMediaItems(c.local, c.index.coerceIn(0, (c.local.size - 1).coerceAtLeast(0)), c.positionMs)
        device.playWhenReady = c.resumeOnDevice
        device.prepare()
    }

    override fun onCastTakenBack() {
        val c = cast
        if (onReceiver && c != null) {
            // Before the session's end: it goes with the title.
            c.resumeOnDevice = c.playWhenReady
            return
        }
        val until = playingUntil ?: return
        playingUntil = null
        if (clock() <= until && !onReceiver && device.mediaItemCount > 0) device.playWhenReady = true
    }

    override fun onCastSessionEnded() {
        session?.let(sessions::close)
        session = null
        kept = null
        if (phase == Phase.SUSPENDED) {
            stopJobs()
            cast = null
            phase = Phase.NONE
            invalidateState()
            handler.post { onOutputChanged() }
        }
    }

    // ---- A suspended session.

    override fun onCastSuspended() {
        // Before the transfer, [toDevice] sees it by itself.
        if (!onReceiver) kept?.let(::suspended)
    }

    private fun suspended(c: CastTitle) {
        if (phase == Phase.SUSPENDED) return
        cast = c
        phase = Phase.SUSPENDED
        watchdog?.cancel()
        // The framework may never say: a phone that stays away gives up. Closing the session also stops a receiver out of reach, at its next read.
        watchdog = scope.launch {
            delay(SUSPEND_LIMIT_MS)
            if (phase == Phase.SUSPENDED) {
                Log.i(TAG, "the Cast session did not come back")
                say(R.string.cast_dropped)
                leave()
            }
        }
        invalidateState()
    }

    /** The session is back. What the receiver does is the question: it played on, so nothing is sent until it has answered. */
    private fun resume(c: CastTitle) {
        stopJobs()
        device.playWhenReady = false
        cast = c
        phase = Phase.RESUMING
        loadSent = false
        pendingSeek = false
        pressedWhileResuming = false
        failureAnswered = false
        doubtSince = null
        boundaryAt = null
        sentIndex = c.index
        sentPositionMs = c.positionMs
        sentAt = clock()
        handler.post { link.requestStatus() }
        watchdog = scope.launch {
            delay(RESUME_PROOF_MS)
            if (phase == Phase.RESUMING && cast === c) {
                val active = attached
                if (active != null && holdsPlaying(active) && c.owns(active.currentMediaItem)) becomeActive(c, active, fromReceiver = true) else sendAgain(c)
            }
        }
        startTicker()
    }

    /** The receiver has lost the title: back to where it was, doing what it did. The session is still good, the server knows nothing of the hiccup. */
    private fun sendAgain(c: CastTitle) {
        val opened = session
        if (opened == null || bases.isEmpty()) {
            load(c)
            return
        }
        stopJobs()
        phase = Phase.LOADING
        baseIndex = 0
        send(c, opened)
    }

    // ---- Loading on the receiver.

    /** A new session each time: an old one may be gone on the server (36 h, a restart, another title's open). */
    private fun load(c: CastTitle) {
        val loading = ++generation
        stopJobs()
        phase = Phase.LOADING
        loadSent = false
        pendingSeek = false
        failureAnswered = false
        receiverError = null
        statusAsked = false
        doubtSince = null
        boundaryAt = null
        baseIndex = 0
        session?.let(sessions::close)
        session = null
        // Never in place: a transfer calls this before Media3 made the receiver the active player.
        scope.launch(Dispatchers.Main) {
            opening.withLock {
                if (loading != generation) return@launch
                val opened = open(c, loading) ?: return@launch
                if (loading != generation) {
                    sessions.close(opened)
                    return@launch
                }
                session = opened
                bases = try { sessions.addresses(c.serverId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                if (loading != generation) return@launch
                when {
                    // The account was left meanwhile.
                    bases.isEmpty() -> failed(R.string.cast_open_failed)
                    // Files the server leaves out, or a stale database: either way no queue for this timeline.
                    opened.tracks.size != c.local.size -> failed(R.string.cast_files_differ)
                    else -> send(c, opened)
                }
            }
        }
    }

    /** The session for [c]; null if that failed (and the person was told). */
    private suspend fun open(c: CastTitle, loading: Int): OpenCastSession? = try {
        sessions.open(c.serverId, c.key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: CastServerOffline) {
        if (loading == generation) failed(R.string.cast_server_offline)
        null
    } catch (e: Exception) {
        Log.w(TAG, "the session for a cast did not open", e)
        if (loading == generation) failed(R.string.cast_open_failed)
        null
    }

    private fun send(c: CastTitle, opened: OpenCastSession) {
        val active = attached ?: return
        val start = c.index.coerceIn(0, (c.local.size - 1).coerceAtLeast(0))
        val items = ReceiverItems.forLoad(opened, bases[baseIndex], c.local, start, c.positionMs)
        if (items == null) {
            failed(R.string.cast_too_many_files)
            return
        }
        sentIndex = start
        sentPositionMs = c.positionMs
        sentAt = clock()
        statusAsked = false
        active.playWhenReady = c.playWhenReady
        active.setMediaItems(items, start, c.positionMs)
        loadSent = true
        pendingSeek = false
        // A failure the status still shows is the previous media's: the server closes a device's old session when it opens the next.
        failureAnswered = link.activity() == ReceiverActivity.FAILED
        watch(c, loadLimitMs / bases.size.coerceAtLeast(1))
        startTicker()
        invalidateState()
    }

    private fun watch(c: CastTitle, ms: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ms)
            if (phase == Phase.LOADING && cast === c) limitReached(c)
        }
    }

    /** An address has had its share of the limit. If the receiver does play but never proves where, another address is no answer. */
    private fun limitReached(c: CastTitle) {
        val active = attached
        if (active == null || active.playbackState != Player.STATE_READY || !c.owns(active.currentMediaItem)) {
            Log.w(TAG, "the receiver did not load the title in time")
            receiverFailed(c)
            return
        }
        val left = bases.size - 1 - baseIndex
        if (left > 0) {
            baseIndex = bases.lastIndex
            watch(c, loadLimitMs / bases.size * left)
        } else {
            Log.w(TAG, "the receiver plays but does not prove its position")
            failed(R.string.cast_no_position)
        }
    }

    /** The cast ends, for a reason the person gets to read; the title waits paused on this device. */
    private fun failed(@StringRes reason: Int) {
        say(reason)
        cast?.apply {
            playWhenReady = false
            resumeOnDevice = false
        }
        leave()
    }

    private fun leave() {
        generation++
        stopJobs()
        if (!onReceiver) {
            // Suspended: the title is here already, only the session is left to end.
            onCastSessionEnded()
            handler.post { link.endSession(stopReceiver = true) }
            return
        }
        phase = Phase.LEAVING
        invalidateState()
        handler.post { link.endSession(stopReceiver = true) }
    }

    private fun stopJobs() {
        watchdog?.cancel()
        watchdog = null
        ticker?.cancel()
        ticker = null
    }

    /** Once a second: the position of a playing receiver comes without any event. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                evaluate()
            }
        }
    }

    // ---- What the receiver says.

    override fun onReceiverStatus() = evaluate()

    override fun onReceiverError(detailedCode: Int?) {
        receiverError = detailedCode
        evaluate()
    }

    private fun holdsPlaying(active: Player) = active.playbackState == Player.STATE_READY || active.playbackState == Player.STATE_BUFFERING

    /** The status's content id knows it also where the queue's item is not known yet (a large queue's `MediaItem.EMPTY`). */
    private fun holdsOwn(c: CastTitle, active: Player) =
        active.mediaItemCount > 0 && (c.owns(active.currentMediaItem) || link.contentId()?.startsWith("${c.key}#") == true)

    private fun ownInQueue(c: CastTitle, active: Player) =
        (0 until active.mediaItemCount).any { c.owns(active.getMediaItemAt(it)) } || link.contentId()?.startsWith("${c.key}#") == true

    /** The only place where the receiver changes the phase; runs at every event of the Cast player, every status, every second. */
    private fun evaluate() {
        val c = cast ?: return
        if (!onReceiver) return
        val active = attached ?: return
        val activity = link.activity().let {
            // A load interrupts what plays, and a queue's next file may be such a load.
            if (it == ReceiverActivity.INTERRUPTED) (if (ownInQueue(c, active)) ReceiverActivity.BUSY else ReceiverActivity.STOPPED) else it
        }
        if (activity != ReceiverActivity.FAILED) failureAnswered = false
        // Another title's failure says nothing about this one: the server closes the old session when it opens the next.
        val failure = activity == ReceiverActivity.FAILED && !failureAnswered && link.contentId()?.startsWith("${c.key}#") != false
        when (phase) {
            Phase.LOADING -> when {
                !loadSent -> Unit
                failure -> receiverFailed(c)
                active.playbackState == Player.STATE_READY && c.owns(active.currentMediaItem) ->
                    if (startProven(c, active)) becomeActive(c, active, fromReceiver = false) else askForStatus()
            }
            Phase.RESUMING -> when {
                activity == ReceiverActivity.FAILED || activity == ReceiverActivity.FINISHED || activity == ReceiverActivity.STOPPED -> sendAgain(c)
                holdsPlaying(active) && c.owns(active.currentMediaItem) && resumeProven(active) -> becomeActive(c, active, fromReceiver = true)
            }
            Phase.ACTIVE -> when {
                failure -> receiverFailed(c)
                activity == ReceiverActivity.FINISHED && atTheEnd(c) -> ended(c)
                activity == ReceiverActivity.STOPPED -> toStopped(c)
                active.mediaItemCount == 0 && activity == ReceiverActivity.BUSY -> queueGone(c)
                activity == ReceiverActivity.FINISHED || !holdsOwn(c, active) || active.playbackState == Player.STATE_IDLE -> doubt(c)
                else -> {
                    doubtSince = null
                    remember(c, active)
                }
            }
            // It plays its own title after all: an interruption that was none, or the TV's remote.
            Phase.STOPPED -> if (active.playbackState == Player.STATE_READY && c.owns(active.currentMediaItem)) becomeActive(c, active, fromReceiver = true)
            else -> Unit
        }
    }

    /**
     * After a load, the receiver's position may reach the progress only once it reports the start file at a plausible
     * position: its index only moves with a status, `lastReportedPositionMs` only with a report, and a first status can say
     * index 0 of N or position 0. No proof: it asks for a fresh status, a report may come any second, and [loadLimitMs] ends it.
     */
    private fun startProven(c: CastTitle, active: Player): Boolean {
        val reach = (clock() - sentAt).coerceAtLeast(0) * MAX_SPEED + POSITION_SLACK_MS
        val position = active.currentPosition
        val index = active.currentMediaItemIndex
        if (index == sentIndex) return position in (sentPositionMs - POSITION_SLACK_MS)..(sentPositionMs + reach)
        // It started near a file's end and has crossed the boundary meanwhile.
        val fileMs = c.local.getOrNull(sentIndex)?.fileDurationMs() ?: return false
        return index == sentIndex + 1 && sentPositionMs + reach >= fileMs && position <= reach
    }

    /** It played on since the suspend: at or after where it was. Otherwise the fresh status may set it right, and the limit decides. */
    private fun resumeProven(active: Player): Boolean {
        val index = active.currentMediaItemIndex
        return index > sentIndex || (index == sentIndex && active.currentPosition >= sentPositionMs - POSITION_SLACK_MS)
    }

    private fun askForStatus() {
        if (statusAsked) return
        statusAsked = true
        link.requestStatus()
    }

    private fun becomeActive(c: CastTitle, active: Player, fromReceiver: Boolean) {
        stopJobs()
        phase = Phase.ACTIVE
        doubtSince = null
        boundaryAt = null
        statusAsked = false
        c.index = if (pendingSeek) c.index else active.currentMediaItemIndex
        if (fromReceiver) {
            if (pressedWhileResuming) active.playWhenReady = c.playWhenReady else c.playWhenReady = active.playWhenReady
            if (!pendingSeek) c.positionMs = active.currentPosition.coerceAtLeast(0)
        } else {
            if (!pendingSeek) c.positionMs = active.currentPosition.coerceAtLeast(0)
            if (active.playWhenReady != c.playWhenReady) active.playWhenReady = c.playWhenReady
        }
        // A load carries no speed (Media3 b/432716880), and a seek while it loaded could not be sent.
        if (abs(active.playbackParameters.speed - c.speed) > SPEED_SLACK) active.setPlaybackParameters(PlaybackParameters(c.speed))
        if (pendingSeek) {
            pendingSeek = false
            active.seekTo(c.index, c.positionMs)
        }
        pressedWhileResuming = false
        startTicker()
        invalidateState()
        onOutputChanged()
    }

    /**
     * The receiver's position stays known, for the way back and for a status that has none any more. Only from a state that plays.
     * When the receiver crosses into the next file by itself, its index moves with a status and its position only with the next report:
     * until that is a sane position in the new file the player stands at the start of it ([trusted]).
     */
    private fun remember(c: CastTitle, active: Player) {
        if (!holdsPlaying(active) || !c.owns(active.currentMediaItem)) return
        val index = active.currentMediaItemIndex
        if (index != c.index) {
            c.index = index
            c.positionMs = 0
            boundaryAt = clock()
        }
        c.playWhenReady = active.playWhenReady
        val settled = boundaryAt == null || positionSane(active)
        if (settled) {
            if (boundaryAt != null) {
                boundaryAt = null
                invalidateState()
            }
            c.positionMs = active.currentPosition.coerceAtLeast(0)
        } else {
            invalidateState()
        }
    }

    /** After a boundary: at most what can have been played since. After [DOUBT_MS] it is what the receiver says (a TV remote can jump too). */
    private fun positionSane(active: Player): Boolean {
        val since = (clock() - (boundaryAt ?: return true)).coerceAtLeast(0)
        return since >= DOUBT_MS || active.currentPosition <= since * MAX_SPEED + POSITION_SLACK_MS
    }

    /** The state on a receiver can stand as it is. Pure: [getState] asks. */
    private fun trusted(c: CastTitle): Boolean {
        val active = player
        if (!holdsPlaying(active) || active.mediaItemCount == 0 || !c.owns(active.currentMediaItem)) return false
        return if (active.currentMediaItemIndex != c.index) active.currentPosition <= POSITION_SLACK_MS else positionSane(active)
    }

    /**
     * The status says finished, but it is a finished *title* only at the true end of the last file, by the last position it reported
     * while playing. [FINISHED_TOLERANCE_MS] covers that a report is up to a second old, at most 2× that in book time, and that ABS's
     * duration of a file can be off by a few seconds (VBR): a title that truly ended is within it, the start of a last file never is.
     */
    private fun atTheEnd(c: CastTitle): Boolean {
        val last = c.local.lastIndex
        if (c.index != last) return false
        val end = c.local[last].fileDurationMs() ?: return false
        return end - c.positionMs <= FINISHED_TOLERANCE_MS
    }

    private fun ended(c: CastTitle) {
        stopJobs()
        phase = Phase.ENDED
        c.index = c.local.lastIndex.coerceAtLeast(0)
        c.positionMs = c.local.getOrNull(c.index)?.fileDurationMs() ?: c.positionMs
        c.playWhenReady = false
        invalidateState()
    }

    private fun toStopped(c: CastTitle) {
        stopJobs()
        phase = Phase.STOPPED
        c.playWhenReady = false
        invalidateState()
    }

    /**
     * The receiver's state makes no sense for a title it plays: a finished status elsewhere than at the end, an item of another
     * sender, idle without a reason. A moment of that ends nothing (a file boundary, the index's fallback to 0 while a queue is
     * fetched). It asks for a fresh status, and [DOUBT_MS] of it means the receiver holds nothing of ours: the title waits paused.
     */
    private fun doubt(c: CastTitle) {
        val since = doubtSince ?: clock().also {
            doubtSince = it
            link.requestStatus()
            invalidateState()
        }
        if (clock() - since >= DOUBT_MS) toStopped(c)
    }

    /** The receiver's queue is gone for a moment (a known hiccup of the Cast SDK): the title stays, the load's limit watches. */
    private fun queueGone(c: CastTitle) {
        stopJobs()
        phase = Phase.LOADING
        loadSent = true
        sentIndex = c.index
        sentPositionMs = c.positionMs
        sentAt = clock()
        statusAsked = false
        invalidateState()
        watch(c, loadLimitMs)
        startTicker()
    }

    /**
     * The receiver could not load or play. A load may still try the next address; a format the device cannot play or a
     * cast that had worked has nowhere else to go.
     */
    private fun receiverFailed(c: CastTitle) {
        failureAnswered = true
        val code = receiverError
        receiverError = null
        val format = code == MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED || code == MediaError.DetailedErrorCode.MEDIA_DECODE
        val opened = session
        if (!format && phase == Phase.LOADING && opened != null && baseIndex + 1 < bases.size) {
            baseIndex++
            Log.i(TAG, "the receiver cannot reach the first address, trying the next")
            send(c, opened)
            return
        }
        failed(
            when {
                format -> R.string.cast_format_failed
                bases.getOrNull(baseIndex)?.isHttps == false -> R.string.cast_unreachable_http
                else -> R.string.cast_unreachable
            },
        )
    }

    // ---- The player's state on a receiver.

    override fun getState(): SimpleBasePlayer.State {
        val live = super.getState()
        val c = cast
        return when {
            phase == Phase.SUSPENDED && c != null -> masked(live, c)
            !onReceiver -> live
            phase == Phase.ACTIVE && c != null && trusted(c) -> live
            c != null -> masked(live, c)
            // Being left with nothing in it (a stop, a sign-out): whatever the receiver still reports is over.
            phase == Phase.LEAVING -> emptied(live)
            else -> live
        }
    }

    private fun masked(live: SimpleBasePlayer.State, c: CastTitle): SimpleBasePlayer.State {
        val playlist = c.local.mapIndexed { i, item ->
            SimpleBasePlayer.MediaItemData.Builder("${c.key}#$i").setMediaItem(item)
                .setDurationUs(item.fileDurationMs()?.let { it * 1000 } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val busy = phase == Phase.LOADING || phase == Phase.RESUMING || phase == Phase.ACTIVE || phase == Phase.SUSPENDED
        val state = when {
            phase == Phase.ENDED -> Player.STATE_ENDED
            busy -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return live.buildUpon()
            .setAvailableCommands(live.availableCommands.buildUpon().addAll(*MASKED_COMMANDS).build())
            .setDeviceInfo(if (onReceiver) live.deviceInfo else DEVICE_INFO_SUSPENDED)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(c.index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(c.positionMs)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(c.positionMs))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .setPlaybackState(state)
            .setPlayWhenReady(busy && c.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(c.speed))
            .setIsLoading(busy)
            .setPlayerError(null)
            .clearPositionDiscontinuity()
            .build()
    }

    private fun emptied(live: SimpleBasePlayer.State): SimpleBasePlayer.State = live.buildUpon()
        .setPlaylist(emptyList<SimpleBasePlayer.MediaItemData>())
        .setPlaybackState(Player.STATE_IDLE)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setIsLoading(false)
        .setPlayerError(null)
        .clearPositionDiscontinuity()
        .build()

    // ---- What the app asks of the player, on a receiver. On the device everything just goes through.

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (!casting) return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        val key = mediaItems.firstOrNull()?.titleKey()
        if (key == null) {
            Log.w(TAG, "an item without a title cannot be cast")
            return Futures.immediateVoidFuture()
        }
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val position = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0)
        val c = cast
        val sameTitle = c != null && c.serverId == key.first && c.key == key.second
        when {
            phase == Phase.SUSPENDED -> {
                val local = mediaItems.filterNot(ReceiverItems::isReceiverItem)
                if (sameTitle && c != null) {
                    if (local.isNotEmpty()) {
                        c.local = local
                        putOnDevice(c)
                    }
                } else {
                    // Nothing can reach the receiver, and nothing starts by itself: it waits here, and the cast is over.
                    say(R.string.cast_suspended_title_here)
                    putOnDevice(CastTitle(key.first, key.second, mediaItems, index, position, playWhenReady = false, speed = c?.speed ?: 1f))
                    leave()
                }
            }
            DeviceLibrary.isLocal(key.first, key.second) -> {
                // Never by itself on another speaker: it waits on this device, paused, and the player says why.
                cast = CastTitle(key.first, key.second, mediaItems, index, position, playWhenReady = false, speed = c?.speed ?: 1f)
                failed(R.string.cast_refused_device_title)
            }
            sameTitle && c != null -> {
                // The same title with other sources on this device (a finished download, a new address) or another position.
                c.local = mediaItems.filterNot(ReceiverItems::isReceiverItem).ifEmpty { c.local }
                if (index != currentMediaItemIndex || abs(position - currentPosition) > SAME_POSITION_MS) seek(c, index, position)
            }
            else -> {
                val next = CastTitle(key.first, key.second, mediaItems, index, position, c?.playWhenReady ?: playWhenReady, c?.speed ?: playbackParameters.speed)
                cast = next
                load(next)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val c = cast
        if (!casting || c == null) return super.handleSetPlayWhenReady(playWhenReady)
        // Suspended, nothing can reach the receiver, and a press here would be a pretence (or sound on both).
        if (phase == Phase.SUSPENDED) return Futures.immediateVoidFuture()
        c.playWhenReady = playWhenReady
        when (phase) {
            Phase.ACTIVE -> return super.handleSetPlayWhenReady(playWhenReady)
            Phase.LOADING -> if (loadSent) return super.handleSetPlayWhenReady(playWhenReady)
            Phase.RESUMING -> pressedWhileResuming = true
            Phase.STOPPED -> if (playWhenReady) load(c)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val c = cast
        if (!casting || c == null) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (phase == Phase.SUSPENDED) return Futures.immediateVoidFuture()
        when (seekCommand) {
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                val index = if (mediaItemIndex == C.INDEX_UNSET) currentMediaItemIndex else mediaItemIndex
                seek(c, index, if (positionMs == C.TIME_UNSET) 0 else positionMs)
            }
            // Only from outside the app (the session player does its own steps): meaningful only with something playing.
            else -> if (phase == Phase.ACTIVE) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        return Futures.immediateVoidFuture()
    }

    private fun seek(c: CastTitle, index: Int, positionMs: Long) {
        c.index = index
        c.positionMs = positionMs.coerceAtLeast(0)
        when (phase) {
            Phase.ACTIVE -> {
                boundaryAt = null
                attached?.seekTo(c.index, c.positionMs)
            }
            Phase.LOADING -> pendingSeek = loadSent
            Phase.RESUMING -> pendingSeek = true
            Phase.ENDED, Phase.STOPPED -> load(c)
            else -> Unit
        }
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val c = cast
        if (!casting || c == null) return super.handleSetPlaybackParameters(playbackParameters)
        if (phase == Phase.SUSPENDED) return Futures.immediateVoidFuture()
        c.speed = playbackParameters.speed
        onSpeedRequested(playbackParameters.speed)
        return if (phase == Phase.ACTIVE) super.handleSetPlaybackParameters(playbackParameters) else Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (!casting) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        // A receiver's queue is never edited: a replace would interrupt it. It is news for the way back, if it holds no receiver address.
        val c = cast
        val local = mediaItems.filterNot(ReceiverItems::isReceiverItem)
        if (c != null && local.size == mediaItems.size && local.isNotEmpty() && local.all { it.titleKey() == c.serverId to c.key } && toIndex <= c.local.size) {
            c.local = c.local.take(fromIndex) + local + c.local.drop(toIndex)
            if (phase == Phase.SUSPENDED) device.replaceMediaItems(fromIndex, toIndex, local)
            if (phase != Phase.ACTIVE) invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        if (casting) Futures.immediateVoidFuture() else super.handleAddMediaItems(index, mediaItems)

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        if (casting) Futures.immediateVoidFuture() else super.handleMoveMediaItems(fromIndex, toIndex, newIndex)

    /** Removing everything is how the queue is emptied (a sign-out, a switch): the cast ends with it. */
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (!casting) return super.handleRemoveMediaItems(fromIndex, toIndex)
        if (fromIndex == 0 && toIndex >= mediaItemCount) {
            cast = null
            kept = null
            device.clearMediaItems()
            endCast()
        }
        return Futures.immediateVoidFuture()
    }

    /** A stop ends the cast and its session now, so a sign-out behind it still has the token for the close. */
    override fun handleStop(): ListenableFuture<*> {
        if (!casting) return super.handleStop()
        cast?.apply {
            playWhenReady = false
            resumeOnDevice = false
        }
        endCast()
        return Futures.immediateVoidFuture()
    }

    private fun endCast() {
        if (phase == Phase.LEAVING) return invalidateState()
        generation++
        session?.let(sessions::close)
        session = null
        leave()
    }

    override fun handleRelease(): ListenableFuture<*> {
        val wasCasting = casting
        generation++
        stopJobs()
        onCastSessionEnded()
        if (wasCasting) link.endSession(stopReceiver = true)
        return super.handleRelease()
    }

    private fun MediaItem.fileDurationMs(): Long? = mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS)?.takeIf { it > 0 }

    private companion object {
        const val TAG = "CastOutput"
        /** A person's wait for a load, over all addresses. */
        const val LOAD_LIMIT_MS = 60_000L
        /** A receiver that has not answered by then after a resume has lost the title. */
        const val RESUME_PROOF_MS = 10_000L
        /** A session that stays suspended this long is given up. */
        const val SUSPEND_LIMIT_MS = 5 * 60_000L
        /** How long a receiver's state may make no sense before the title stops waiting for it. */
        const val DOUBT_MS = 10_000L
        const val TICK_MS = 1_000L
        /** A receiver plays at most this fast. */
        const val MAX_SPEED = 2
        /** What a seek lands before its target, and what a report may be off. */
        const val POSITION_SLACK_MS = 5_000L
        /** See [atTheEnd]. */
        const val FINISHED_TOLERANCE_MS = 15_000L
        /** Between "this phone" in the system's output switcher and the session's end, in whichever order they arrive. */
        const val TAKE_BACK_WINDOW_MS = 15_000L
        /** The same title set again at about the same position (new sources) is no seek. */
        const val SAME_POSITION_MS = 3_000L
        const val SPEED_SLACK = 0.001f

        /** While suspended Media3 sits on the device, but the title is still the receiver's: the session and the volume keys do not change sides. */
        val DEVICE_INFO_SUSPENDED: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(20).build()

        /** What the masked state offers although `RemoteCastPlayer` is empty: the app works on the title as usual. */
        val MASKED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_PREPARE,
        )
    }
}
