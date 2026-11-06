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
 *   after a load, its index only moves with a status and may fall back to 0, and its position only a new status can correct, so a
 *   status may pair the wrong file with the wrong position. What reaches the progress is the title's own position until the receiver
 *   proves otherwise: the start file at a plausible position after a load ([startProven]), a sane position after a file boundary
 *   ([remember]). Without a proof it asks for a fresh status, every few seconds. A load gives up at [loadLimitMs] unless the receiver
 *   does play the title: that TV plays on, and only the proof is missing ([limitReached]).
 * - **An end is an end, only at the end.** The receiver's idle state with reason "finished" becomes [Player.STATE_ENDED], which the
 *   progress and the queue are waiting for, only near the true end of the last file ([atTheEnd]). Anywhere else it is a file
 *   boundary or a doubt: nothing ends, and a doubt that lasts [DOUBT_MS] leaves the title paused, with the play button loading it.
 * - **A load failure ends the cast**, with the reason said plainly; paused here, position kept.
 * - **A suspended session leaves the receiver alone.** The phone's Wi-Fi drops, Media3 moves to the device, and the receiver plays
 *   on. The player then shows neither playing nor paused (buffering, on the receiver), makes no sound, prepares no stream and sends
 *   nothing: nothing here can reach the TV. On resume it finds out what the receiver does, and takes that over; only a failure or
 *   silence loads again.
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

        /** What it was on the receiver when it left there: a resume goes back to that. */
        var left = Phase.NONE

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

        /**
         * The Cast session is ending; the title goes back to the device. Also with Media3 on the device already, once another title
         * took over from a suspended cast: then only a play waits for the end, see [otherTitleHere].
         */
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
            playsUnseen = false
        }

    /** The title that went to the device with the Cast session not over yet: a suspend may come back for it. */
    private var kept: CastTitle? = null
    /** The framework said the session is over. It may say so before Media3's transfer (a TV switched off), which then keeps nothing. */
    private var sessionOver = false
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
    /** The content ids of the queue sent last, by file: `RemoteCastPlayer` may not have placed the current one yet, the status names it anyway. */
    private var sentIds: List<String> = emptyList()
    /** Where the receiver can reach the server, and how far down the list this load is. */
    private var bases: List<HttpUrl> = emptyList()
    private var baseIndex = 0
    /** The receiver's failure was answered: the status keeps saying so until the next load has an effect. */
    private var failureAnswered = false
    /** The same for a status that says finished: it may be what the title did the last time. */
    private var finishedAnswered = false
    /** The limit has passed with the receiver playing the title and no plausible place said. Only a proof, or a new load, undoes it: see [limitReached]. */
    private var unproven = false
    /** A hold: what happens while [unproven], on the receiver and without a stop. */
    private val holding: Boolean get() = unproven && phase == Phase.LOADING
    /** The receiver plays and the player cannot show it, see [CastActivity.PLAYING_UNSEEN]. */
    private var playsUnseen = false
    private var pendingSeek = false
    /** A person pressed play or pause while the resume could not tell the receiver. */
    private var pressedWhileResuming = false
    private var receiverError: Int? = null
    private var statusAskedAt: Long? = null
    /** Since when the receiver's state makes no sense for a title it plays; see [doubt]. */
    private var doubtSince: Long? = null
    /** When the receiver last crossed a file boundary by itself; its position is the old file's until a report says otherwise. */
    private var boundaryAt: Long? = null
    /** A seek across files went out: a status with another file soon after is the old one's, see [remember]. */
    private var seekAt: Long? = null
    /** Since when the receiver says a file that is neither the title's nor the next one: see [remember]. */
    private var otherFileSince: Long? = null
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
        if (sourcePlayer === device) toReceiver() else toDevice(sourcePlayer)
    }

    private fun toReceiver() {
        receiverError = null
        sessionOver = false
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
    private fun toDevice(source: Player) {
        generation++
        stopJobs()
        val c = cast
        val was = phase
        cast = null
        phase = Phase.NONE
        loadSent = false
        pendingSeek = false
        if (c == null) return
        c.left = was
        playingUntil = if (was == Phase.ACTIVE && c.playWhenReady) clock() + TAKE_BACK_WINDOW_MS else null
        when {
            sessionOver -> putOnDevice(c)
            link.isSuspended() -> {
                kept = c
                putOnDevice(c, prepare = false)
                // Media3 prepares the device after this unless the source is idle, and stops the source anyway: this only comes first.
                source.stop()
                suspended(c)
            }
            else -> {
                putOnDevice(c)
                kept = c
            }
        }
        handler.post { onOutputChanged() }
    }

    private fun putOnDevice(c: CastTitle, prepare: Boolean = true) {
        device.setPlaybackParameters(PlaybackParameters(c.speed))
        device.setMediaItems(c.local, c.index.coerceIn(0, (c.local.size - 1).coerceAtLeast(0)), c.positionMs)
        device.playWhenReady = prepare && c.resumeOnDevice
        if (prepare) device.prepare()
    }

    override fun onCastTakenBack() {
        val c = cast
        if (casting && c != null) {
            // Before the session's end: it goes with the title. Nothing sounds here while the receiver may play on.
            c.resumeOnDevice = c.playWhenReady
            return
        }
        val until = playingUntil ?: return
        playingUntil = null
        if (clock() <= until && !onReceiver && device.mediaItemCount > 0) device.playWhenReady = true
    }

    override fun onCastSessionEnded() {
        sessionOver = true
        session?.let(sessions::close)
        session = null
        kept = null
        if (phase == Phase.SUSPENDED) {
            stopJobs()
            val c = cast
            cast = null
            phase = Phase.NONE
            // Now it really is back. A phone without a network fails here, and the player keeps the reason it was told first.
            if (c != null) {
                device.playWhenReady = c.resumeOnDevice
                device.prepare()
            }
            invalidateState()
            handler.post { onOutputChanged() }
        } else if (phase == Phase.LEAVING && !onReceiver) {
            endHold()
        }
    }

    /**
     * Another title while suspended: nothing can reach the receiver, and what the person reads is that it waits here. So it does: the
     * `play()` that belongs to the same request starts nothing, until the framework says the session is over, or [HOLD_MS] if it
     * never says. A press after that plays. Everything else already is the device's.
     */
    private fun otherTitleHere(other: CastTitle) {
        say(R.string.cast_suspended_title_here)
        generation++
        stopJobs()
        session?.let(sessions::close)
        session = null
        kept = null
        cast = null
        // "This phone" from now on would start this title, not take the cast's back.
        playingUntil = null
        phase = Phase.LEAVING
        putOnDevice(other)
        invalidateState()
        watchdog = scope.launch {
            delay(HOLD_MS)
            if (phase == Phase.LEAVING && !onReceiver) endHold()
        }
        handler.post {
            link.endSession(stopReceiver = true)
            onOutputChanged()
        }
    }

    private fun endHold() {
        stopJobs()
        phase = Phase.NONE
        invalidateState()
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
        // The title waits on the device for the way back, not prepared: it may all come back, and a phone without a network has no stream to load.
        device.playWhenReady = false
        device.stop()
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

    /**
     * The session is back. What the receiver does is the question: it played on, so nothing is sent until it has answered. Only a
     * failure or silence may send again. The status may be the old one for a moment: a client may keep it through a suspend, and what
     * the fresh one says then is handled like anywhere else.
     */
    private fun resume(c: CastTitle) {
        stopJobs()
        device.playWhenReady = false
        cast = c
        c.resumeOnDevice = false
        handler.post { link.requestStatus() }
        // It held nothing of ours before the suspend: it goes back to that, and only a press loads.
        when (c.left) {
            Phase.ENDED -> return ended(c)
            Phase.STOPPED -> return toStopped(c)
            else -> Unit
        }
        // What it plays after the gap was not proven before it either.
        if (unproven) return backToHold(c)
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
        watchdog = scope.launch {
            delay(RESUME_PROOF_MS)
            if (phase == Phase.RESUMING && cast === c) {
                val active = attached
                if (active != null && holdsPlaying(active) && holdsOwn(c, active)) becomeActive(c, active, fromReceiver = true) else sendAgain(c)
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
        unproven = false
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
        sentIds = items.map { it.mediaId }
        statusAskedAt = null
        unproven = false
        doubtSince = null
        active.playWhenReady = c.playWhenReady
        active.setMediaItems(items, start, c.positionMs)
        loadSent = true
        pendingSeek = false
        // A failure the status still shows is the previous media's: the server closes a device's old session when it opens the next.
        failureAnswered = link.activity() == ReceiverActivity.FAILED
        finishedAnswered = link.activity() == ReceiverActivity.FINISHED
        watch(c, shareOfTheLimit(baseIndex))
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

    /** The first address is the likely one and gets two thirds; a local one after it answers fast or not at all. Together about the limit. */
    private fun shareOfTheLimit(address: Int): Long = when {
        bases.size <= 1 -> loadLimitMs
        address == 0 -> loadLimitMs * 2 / 3
        else -> loadLimitMs / 3 / (bases.size - 1)
    }

    /**
     * An address has had its share of the limit. A receiver that does play the title has been reached: no other address, and no word
     * about reaching it. If all that is missing is a plausible place, the TV plays on, since stopping what a person asked for over a
     * doubt about bookkeeping is the worst of the answers, and taking its position as it is would overwrite the listening position
     * here and on the server. So nothing reaches the progress, it is said once, and it stays so until a status says a plausible place,
     * a person's seek gives it one, a person ends the cast, or the receiver really stops: a moment of buffering at a look is none, and
     * a look that finds nothing there is, and costs nothing if it was wrong ([backToHold]).
     */
    private fun limitReached(c: CastTitle) {
        val active = attached
        when {
            holding -> if (active != null && holdsPlaying(active)) watch(c, loadLimitMs) else toStopped(c)
            active != null && active.playbackState == Player.STATE_READY && playsOurs(c, active) -> {
                Log.w(TAG, "the receiver plays but does not prove its position")
                unproven = true
                say(R.string.cast_position_unknown)
                watch(c, loadLimitMs)
            }
            else -> {
                Log.w(TAG, "the receiver did not load the title in time")
                receiverFailed(c)
            }
        }
    }

    /** A hold that a stop or a suspend interrupted goes on where it was: whatever the receiver says now still has to pass [startProven]. */
    private fun backToHold(c: CastTitle) {
        stopJobs()
        phase = Phase.LOADING
        loadSent = true
        failureAnswered = false
        statusAskedAt = null
        invalidateState()
        watch(c, loadLimitMs)
        startTicker()
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
        if (phase == Phase.SUSPENDED) {
            // The title is here already, only the session is left to end.
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

    /**
     * Posted: the framework tells this runtime before `RemoteCastPlayer`, whose index would still be the old file's while the
     * position and the content id are the new one's already. Its news reaches the Cast player ahead of anything posted.
     */
    override fun onReceiverStatus() {
        handler.post { evaluate() }
    }

    override fun onReceiverError(detailedCode: Int?) {
        receiverError = detailedCode
        evaluate()
    }

    private fun holdsPlaying(active: Player) = active.playbackState == Player.STATE_READY || active.playbackState == Player.STATE_BUFFERING

    /** The status's content id knows it also where the queue's item is not known yet (a large queue's `MediaItem.EMPTY`). */
    private fun holdsOwn(c: CastTitle, active: Player) =
        active.mediaItemCount > 0 && (c.owns(active.currentMediaItem) || link.contentId()?.startsWith("${c.key}#") == true)

    /** The status's content id is the receiver's own word; Media3's item, which may be behind it, counts only when there is none. */
    private fun playsOurs(c: CastTitle, active: Player): Boolean {
        val content = link.contentId() ?: return c.owns(active.currentMediaItem)
        return content.startsWith("${c.key}#")
    }

    /** The file the receiver is at: the status's content id knows it where Media3's index still is its fallback to 0. */
    private fun receiverIndex(active: Player): Int {
        val named = sentIds.indexOf(link.contentId())
        return if (named >= 0) named else active.currentMediaItemIndex
    }

    private fun holdsOther(c: CastTitle, active: Player) = holdsPlaying(active) && link.contentId()?.startsWith("${c.key}#") == false

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
        if (activity != ReceiverActivity.FINISHED) finishedAnswered = false
        // Another title's failure says nothing about this one: the server closes the old session when it opens the next.
        val failure = activity == ReceiverActivity.FAILED && !failureAnswered && link.contentId()?.startsWith("${c.key}#") != false
        when (phase) {
            Phase.LOADING -> when {
                !loadSent -> Unit
                failure -> receiverFailed(c)
                activity == ReceiverActivity.FINISHED && !finishedAnswered && link.contentId()?.startsWith("${c.key}#") != false -> finishedAtOnce(c)
                holding && (activity == ReceiverActivity.STOPPED || holdsOther(c, active)) -> toStopped(c)
                else -> {
                    doubtSince = null
                    if (active.playbackState == Player.STATE_READY) {
                        if (startProven(c, active)) becomeActive(c, active, fromReceiver = false) else askForStatus()
                    }
                }
            }
            Phase.RESUMING -> when {
                activity == ReceiverActivity.FAILED -> sendAgain(c)
                // Stopped or played out during the gap: nothing here restarts a TV.
                activity == ReceiverActivity.STOPPED -> toStopped(c)
                activity == ReceiverActivity.FINISHED -> if (atTheEnd(c)) ended(c) else toStopped(c)
                // Another sender's title is no silence.
                holdsOther(c, active) -> toStopped(c)
                holdsPlaying(active) && holdsOwn(c, active) && resumeProven(active) -> becomeActive(c, active, fromReceiver = true)
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
                    // Proven by the content id with Media3 behind: a steady receiver sends nothing until a pause or a file's end, and an answer is what moves Media3.
                    if (!trusted(c)) askForStatus()
                }
            }
            // It plays its own title after all: an interruption that was none, or the TV's remote. What was never proven still has to be.
            Phase.STOPPED -> if (active.playbackState == Player.STATE_READY && playsOurs(c, active)) {
                if (unproven) {
                    c.playWhenReady = active.playWhenReady
                    backToHold(c)
                } else {
                    becomeActive(c, active, fromReceiver = true)
                }
            }
            else -> Unit
        }
        if (cast === c && (phase == Phase.LOADING || phase == Phase.ACTIVE)) publishUnseen(c, active)
    }

    /** Masked as buffering while the TV plays: the sleep timer must not take that for a load, or the TV plays past it. */
    private fun publishUnseen(c: CastTitle, active: Player) {
        val unseen = c.playWhenReady && active.playbackState == Player.STATE_READY && holdsOwn(c, active) && (holding || (phase == Phase.ACTIVE && !trusted(c)))
        if (unseen == playsUnseen) return
        playsUnseen = unseen
        publish(if (unseen) CastActivity.PLAYING_UNSEEN else CastActivity.PLAYING)
    }

    /**
     * After a load, the receiver's position may reach the progress only once it reports the start file at a plausible
     * position: its index only moves with a status and may fall back to 0, the position is what the last status said, and a first
     * status can say index 0 of N or position 0. Where the status has a content id, that names the file. No proof: it asks again.
     */
    private fun startProven(c: CastTitle, active: Player): Boolean {
        if (!playsOurs(c, active)) return false
        val reach = (clock() - sentAt).coerceAtLeast(0) * MAX_SPEED + POSITION_SLACK_MS
        val position = active.currentPosition
        val index = receiverIndex(active)
        if (index == sentIndex) return position in (sentPositionMs - POSITION_SLACK_MS)..(sentPositionMs + reach)
        // It started near a file's end and has crossed the boundary meanwhile.
        val fileMs = c.local.getOrNull(sentIndex)?.fileDurationMs() ?: return false
        return index == sentIndex + 1 && sentPositionMs + reach >= fileMs && position <= reach
    }

    /** It played on since the suspend: at or after where it was. Otherwise the fresh status may set it right, and the limit decides. */
    private fun resumeProven(active: Player): Boolean {
        val index = receiverIndex(active)
        return index > sentIndex || (index == sentIndex && active.currentPosition >= sentPositionMs - POSITION_SLACK_MS)
    }

    /** A wrong position only a new status can correct. A hold has told the person, and asks less often. */
    private fun askForStatus() {
        val asked = statusAskedAt
        if (asked != null && clock() - asked < if (holding) HOLD_ASK_MS else ASK_MS) return
        statusAskedAt = clock()
        link.requestStatus()
    }

    /**
     * The receiver says finished about the load. At the title's end that is the end: this device too just stands at the end of a
     * finished title, ended and paused, and a press starts the last file over (the session's play) or the library the title from the
     * start. Anywhere else it says so plainly, which is no address's fault and no reason to wait for the limit. A moment of it, between
     * two files of a load that started at a file's end, ends nothing.
     *
     * In a hold it has played on for who knows how long, from who knows where: not "straight away", and nothing proves that it was the
     * title's end. It waits paused at the last proven place, not finished.
     */
    private fun finishedAtOnce(c: CastTitle) {
        if (!holding && atTheEnd(c)) {
            ended(c)
            return
        }
        val since = doubtSince ?: clock().also {
            doubtSince = it
            link.requestStatus()
        }
        if (clock() - since >= FINISHED_AT_ONCE_MS) failed(if (holding) R.string.cast_played_out_unseen else R.string.cast_finished_at_once)
    }

    private fun becomeActive(c: CastTitle, active: Player, fromReceiver: Boolean) {
        stopJobs()
        phase = Phase.ACTIVE
        unproven = false
        doubtSince = null
        boundaryAt = null
        otherFileSince = null
        seekAt = null
        statusAskedAt = null
        c.index = if (pendingSeek) c.index else receiverIndex(active)
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
            seekAt = clock()
            active.seekTo(c.index, c.positionMs)
        }
        pressedWhileResuming = false
        startTicker()
        invalidateState()
        onOutputChanged()
    }

    /**
     * The receiver's position stays known, for the way back and for a status that has none any more. Only from a state that plays.
     * When the receiver crosses into the next file by itself, its index moves with a status and its position may lag:
     * until that is a sane position in the new file the player stands at the start of it ([trusted]).
     *
     * Only the next file is a crossing. Any other index (the fallback to 0 of a status whose item cannot be placed), and any index
     * but the target soon after a seek across files (a late status), keeps what is known: that may be what a cast ending right then
     * leaves on this device. [DOUBT_MS] of it means it is true (a TV's remote can jump too).
     */
    private fun remember(c: CastTitle, active: Player) {
        if (!holdsPlaying(active) || !holdsOwn(c, active)) return
        val wasTrusted = trusted(c)
        val index = receiverIndex(active)
        c.playWhenReady = active.playWhenReady
        val seek = seekAt
        if (seek != null) {
            // Not "until the index is the target": `RemoteCastPlayer` shows the target until the seek's result, then the old file until the status.
            if (clock() - seek >= DOUBT_MS) seekAt = null else if (index != c.index) return invalidateState()
        }
        when (index) {
            c.index -> otherFileSince = null
            c.index + 1 -> {
                otherFileSince = null
                c.index = index
                c.positionMs = 0
                boundaryAt = clock()
            }
            else -> {
                val since = otherFileSince ?: clock().also { otherFileSince = it }
                if (clock() - since < DOUBT_MS) return invalidateState()
                otherFileSince = null
                c.index = index
                c.positionMs = active.currentPosition.coerceAtLeast(0)
                boundaryAt = null
            }
        }
        val atBoundary = boundaryAt != null
        if (!atBoundary || positionSane(active)) {
            boundaryAt = null
            val position = active.currentPosition.coerceAtLeast(0)
            // Media3 has not placed the item yet and only the content id says it is ours: its position may be a first status's 0.
            if (c.owns(active.currentMediaItem) || position >= c.positionMs - POSITION_SLACK_MS) c.positionMs = position
        }
        // A masked state shows the position it remembers, and a state that stops being masked has to say so.
        if (atBoundary || !wasTrusted || !trusted(c)) invalidateState()
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
        val shown = active.currentMediaItemIndex
        return when {
            shown != receiverIndex(active) -> false
            shown == c.index -> positionSane(active)
            // The moment a status moved to the next file, before [remember] saw it.
            shown == c.index + 1 && seekAt == null -> active.currentPosition <= POSITION_SLACK_MS
            else -> false
        }
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
        statusAskedAt = null
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
            phase == Phase.ACTIVE && c != null && trusted(c) -> guarded(live, c)
            c != null -> masked(live, c)
            // Being left with nothing in it (a stop, a sign-out): whatever the receiver still reports is over.
            phase == Phase.LEAVING -> emptied(live)
            else -> live
        }
    }

    /**
     * A state's position is read when it is asked for, and the state before a change only at the change, when the Cast player has moved
     * on. What the progress saves there (the old title's place) must not pair the file it stood at with a position of another one.
     */
    private fun guarded(live: SimpleBasePlayer.State, c: CastTitle): SimpleBasePlayer.State = live.buildUpon()
        .setContentPositionMs(SimpleBasePlayer.PositionSupplier { if (trusted(c)) live.contentPositionMsSupplier.get() else c.positionMs })
        .build()

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
                        putOnDevice(c, prepare = false)
                    }
                } else {
                    otherTitleHere(CastTitle(key.first, key.second, mediaItems, index, position, playWhenReady = false, speed = c?.speed ?: 1f))
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
        if (playWhenReady && phase == Phase.LEAVING && !onReceiver) return Futures.immediateVoidFuture()
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
        val across = index != c.index
        c.index = index
        c.positionMs = positionMs.coerceAtLeast(0)
        when (phase) {
            Phase.ACTIVE -> {
                boundaryAt = null
                otherFileSince = null
                seekAt = if (across) clock() else null
                attached?.seekTo(c.index, c.positionMs)
            }
            // No proof will come by itself in a hold: the seek reaches the receiver, and its target is what the next status is measured by.
            Phase.LOADING -> if (holding) {
                sentIndex = c.index
                sentPositionMs = c.positionMs
                sentAt = clock()
                statusAskedAt = null
                attached?.seekTo(c.index, c.positionMs)
            } else {
                pendingSeek = loadSent
            }
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
        // A stopped player is not prepared, and a sign-out goes on to clear it: the title stays on the device as it is.
        if (phase == Phase.SUSPENDED) cast = null
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
        cast = null
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
        /** How often a load without a proof asks for a fresh status. */
        const val ASK_MS = 5_000L
        /** The same once the person has been told, see [limitReached]. */
        const val HOLD_ASK_MS = 30_000L
        /** See [finishedAtOnce]. */
        const val FINISHED_AT_ONCE_MS = 3_000L
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
        /** See [otherTitleHere]. */
        const val HOLD_MS = 5_000L
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
