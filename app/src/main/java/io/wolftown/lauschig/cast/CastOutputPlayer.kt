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
 * - **The title stays in the player while the receiver loads** ([getState]). `RemoteCastPlayer` is empty for seconds until
 *   the first status; a title that vanished would reset the player screen, the notification and the widget and read as stopped.
 * - **An end is an end:** the receiver's idle state with reason "finished" becomes [Player.STATE_ENDED], which the
 *   progress and the queue are waiting for. A load failure ends the cast with the reason said plainly; paused here, position kept.
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
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ForwardingSimpleBasePlayer(device), CastPlayer.TransferCallback, ReceiverListener {

    /** The title of a cast: what it should be, whatever the receiver says at the moment. */
    private class CastTitle(val serverId: String, val key: String, var local: List<MediaItem>, var index: Int, var positionMs: Long, var playWhenReady: Boolean, var speed: Float) {
        /** It plays on here once back on the device. Only if it never left: a cast that ends leaves the title paused. */
        var resumeOnDevice = false
    }

    private enum class Phase {
        /** Nothing on a receiver. */
        NONE,

        /** The session is opening or the receiver is loading. Shown as buffering at the title's position. */
        LOADING,

        /** The receiver plays or holds the title: its own state is the player's. */
        ACTIVE,

        /** The receiver played the queue to its end. */
        ENDED,

        /** The receiver holds nothing (stopped from elsewhere): shown paused, the play button loads it again. */
        STOPPED,

        /** The Cast session is ending; the title goes back to the device. */
        LEAVING,
    }

    private var attached: Player? = null
    private var cast: CastTitle? = null
    private var phase = Phase.NONE
    /** The session of the title on the receiver; stays over a suspended Cast session. */
    private var session: OpenCastSession? = null
    /** Bumped by every new load: a session that opens too late is closed again. */
    private var generation = 0
    private var loadSent = false
    /** Where the receiver can reach the server, and how far down the list this load is. */
    private var bases: List<HttpUrl> = emptyList()
    private var baseIndex = 0
    /** The receiver's failure was answered: the status keeps saying so until the next load has an effect. */
    private var failureAnswered = false
    private var pendingSeek = false
    private var receiverError: Int? = null
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

    // ---- The way there and back: called by Media3's CastPlayer right before it changes the active player.

    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        if (sourcePlayer === device) toReceiver() else toDevice(sourcePlayer)
    }

    private fun toReceiver() {
        receiverError = null
        val key = device.currentMediaItem?.titleKey()
        if (key == null) {
            cast = null
            phase = Phase.NONE
            return
        }
        val playing = device.playWhenReady && device.playbackState != Player.STATE_ENDED
        val c = CastTitle(
            key.first, key.second, List(device.mediaItemCount, device::getMediaItemAt), device.currentMediaItemIndex,
            device.currentPosition.coerceAtLeast(0), playing, device.playbackParameters.speed,
        )
        cast = c
        // No local focus and no local sound while casting; Media3 stops the device right after this.
        device.playWhenReady = false
        when {
            DeviceLibrary.isLocal(c.serverId, c.key) -> refuse(c, R.string.cast_refused_device_title)
            carConnected() -> refuse(c, R.string.cast_refused_car)
            else -> load(c)
        }
    }

    /** The title cannot go there: the session ends again, and it plays on here as if nothing happened. */
    private fun refuse(c: CastTitle, @StringRes reason: Int) {
        c.resumeOnDevice = c.playWhenReady
        say(reason)
        leave()
    }

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
        if (was == Phase.ACTIVE && source.mediaItemCount > 0 && source.currentMediaItem?.titleKey() == c.serverId to c.key) {
            c.index = source.currentMediaItemIndex
            c.positionMs = source.currentPosition.coerceAtLeast(0)
            c.playWhenReady = source.playWhenReady
        }
        playingUntil = if (was == Phase.ACTIVE && c.playWhenReady) clock() + TAKE_BACK_WINDOW_MS else null
        device.setPlaybackParameters(PlaybackParameters(c.speed))
        device.setMediaItems(c.local, c.index.coerceIn(0, (c.local.size - 1).coerceAtLeast(0)), c.positionMs)
        device.playWhenReady = c.resumeOnDevice
        device.prepare()
        handler.post { onOutputChanged() }
    }

    override fun onCastTakenBack() {
        val until = playingUntil ?: return
        playingUntil = null
        if (clock() <= until && !onReceiver && device.mediaItemCount > 0) device.playWhenReady = true
    }

    override fun onCastSessionEnded() {
        session?.let(sessions::close)
        session = null
    }

    // ---- Loading on the receiver.

    private fun load(c: CastTitle) {
        val loading = ++generation
        stopJobs()
        phase = Phase.LOADING
        loadSent = false
        pendingSeek = false
        failureAnswered = false
        receiverError = null
        baseIndex = 0
        // Never in place: a transfer calls this before Media3 made the receiver the active player.
        scope.launch(Dispatchers.Main) {
            if (loading != generation) return@launch
            val opened = openFor(c, loading) ?: return@launch
            if (loading != generation) {
                // Superseded meanwhile; a session in use stays for whoever uses it.
                if (opened !== session) sessions.close(opened)
                return@launch
            }
            session?.takeIf { it !== opened }?.let(sessions::close)
            session = opened
            bases = try { sessions.addresses(c.serverId) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (loading != generation) return@launch
            if (bases.isEmpty() || opened.tracks.size != c.local.size) {
                // No address of this account (it was left meanwhile), or the server's files are not the ones in the database.
                failed(R.string.cast_open_failed)
                return@launch
            }
            send(c, opened)
        }
    }

    /** The session of [c], a new one unless the receiver's is still its own; null if that failed (and the person was told). */
    private suspend fun openFor(c: CastTitle, loading: Int): OpenCastSession? {
        session?.takeIf { it.serverId == c.serverId && it.key == c.key }?.let { return it }
        return try {
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
    }

    private fun send(c: CastTitle, opened: OpenCastSession) {
        val active = attached ?: return
        val items = ReceiverItems.build(opened, bases[baseIndex], c.local)
        active.playWhenReady = c.playWhenReady
        active.setMediaItems(items, c.index.coerceIn(0, items.lastIndex), c.positionMs)
        loadSent = true
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(loadTimeoutMs)
            Log.w(TAG, "the receiver did not load the title in time")
            if (phase == Phase.LOADING && cast === c) receiverFailed(c)
        }
        invalidateState()
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
        stopJobs()
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

    // ---- What the receiver says.

    override fun onReceiverStatus() = evaluate()

    override fun onReceiverError(detailedCode: Int?) {
        receiverError = detailedCode
        evaluate()
    }

    /** The only place where the receiver changes the phase; runs at every event of the Cast player and every status. */
    private fun evaluate() {
        val c = cast ?: return
        if (!onReceiver) return
        val active = attached ?: return
        val activity = link.activity()
        if (activity != ReceiverActivity.FAILED) failureAnswered = false
        val current = active.currentMediaItem?.titleKey()
        val itsOwn = current == c.serverId to c.key
        when (phase) {
            Phase.LOADING -> {
                if (!loadSent) return
                if (activity == ReceiverActivity.FAILED && !failureAnswered) receiverFailed(c)
                else if (active.playbackState == Player.STATE_READY && itsOwn) becomeActive(c, active)
            }
            Phase.ACTIVE -> when {
                activity == ReceiverActivity.FAILED && !failureAnswered -> receiverFailed(c)
                activity == ReceiverActivity.FINISHED && (active.mediaItemCount == 0 || active.currentMediaItemIndex == active.mediaItemCount - 1) -> {
                    remember(c, active)
                    ended(c)
                }
                activity == ReceiverActivity.STOPPED || (active.mediaItemCount > 0 && !itsOwn) -> {
                    // Stopped from elsewhere, or another sender took the device: the title waits here. Its session stays for a new start.
                    stopJobs()
                    phase = Phase.STOPPED
                    c.playWhenReady = false
                    invalidateState()
                }
                active.mediaItemCount == 0 -> {
                    // The receiver's queue is gone for a moment (a known hiccup of the Cast SDK): the title stays, the load's time limit watches.
                    stopJobs()
                    phase = Phase.LOADING
                    loadSent = true
                    invalidateState()
                    watchdog = scope.launch {
                        delay(loadTimeoutMs)
                        if (phase == Phase.LOADING && cast === c) receiverFailed(c)
                    }
                }
                else -> remember(c, active)
            }
            else -> Unit
        }
    }

    private fun becomeActive(c: CastTitle, active: Player) {
        watchdog?.cancel()
        phase = Phase.ACTIVE
        // A load carries no speed (Media3 b/432716880), and a seek while it loaded could not be sent.
        if (abs(active.playbackParameters.speed - c.speed) > SPEED_SLACK) active.setPlaybackParameters(PlaybackParameters(c.speed))
        if (pendingSeek) {
            pendingSeek = false
            active.seekTo(c.index, c.positionMs)
        }
        if (active.playWhenReady != c.playWhenReady) active.playWhenReady = c.playWhenReady
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (phase == Phase.ACTIVE && active.currentMediaItem?.titleKey() == c.serverId to c.key) remember(c, active)
            }
        }
        invalidateState()
        onOutputChanged()
    }

    /** The receiver's position stays known: its status has none once it stopped, and the way back needs it. */
    private fun remember(c: CastTitle, active: Player) {
        if (active.mediaItemCount == 0) return
        c.index = active.currentMediaItemIndex
        c.positionMs = active.currentPosition.coerceAtLeast(0)
        c.playWhenReady = active.playWhenReady
    }

    private fun ended(c: CastTitle) {
        stopJobs()
        phase = Phase.ENDED
        c.index = c.local.lastIndex.coerceAtLeast(0)
        c.positionMs = c.local.getOrNull(c.index)?.fileDurationMs() ?: c.positionMs
        c.playWhenReady = false
        invalidateState()
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
        if (!onReceiver) return live
        val c = cast
        return when {
            phase == Phase.ACTIVE -> live
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
        val state = when (phase) {
            Phase.ENDED -> Player.STATE_ENDED
            Phase.LOADING -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        val wants = phase == Phase.LOADING && c.playWhenReady
        return live.buildUpon()
            .setAvailableCommands(live.availableCommands.buildUpon().addAll(*MASKED_COMMANDS).build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(c.index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(c.positionMs)
            .setContentBufferedPositionMs(SimpleBasePlayer.PositionSupplier.getConstant(c.positionMs))
            .setTotalBufferedDurationMs(SimpleBasePlayer.PositionSupplier.ZERO)
            .setPlaybackState(state)
            .setPlayWhenReady(wants, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(PlaybackParameters(c.speed))
            .setIsLoading(phase == Phase.LOADING)
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
        if (!onReceiver) return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        val key = mediaItems.firstOrNull()?.titleKey()
        if (key == null) {
            Log.w(TAG, "an item without a title cannot be cast")
            return Futures.immediateVoidFuture()
        }
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val position = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs.coerceAtLeast(0)
        val c = cast
        when {
            DeviceLibrary.isLocal(key.first, key.second) -> {
                // Never by itself on another speaker: it waits on this device, paused, and the player says why.
                cast = CastTitle(key.first, key.second, mediaItems, index, position, playWhenReady = false, speed = c?.speed ?: 1f)
                failed(R.string.cast_refused_device_title)
            }
            c != null && c.serverId == key.first && c.key == key.second -> {
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
        if (!onReceiver || c == null) return super.handleSetPlayWhenReady(playWhenReady)
        c.playWhenReady = playWhenReady
        when (phase) {
            Phase.ACTIVE -> return super.handleSetPlayWhenReady(playWhenReady)
            Phase.LOADING -> if (loadSent) return super.handleSetPlayWhenReady(playWhenReady)
            Phase.STOPPED -> if (playWhenReady) load(c)
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val c = cast
        if (!onReceiver || c == null) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
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
            Phase.ACTIVE -> attached?.seekTo(c.index, c.positionMs)
            Phase.LOADING -> pendingSeek = loadSent
            Phase.ENDED, Phase.STOPPED -> load(c)
            else -> Unit
        }
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val c = cast
        if (!onReceiver || c == null) return super.handleSetPlaybackParameters(playbackParameters)
        c.speed = playbackParameters.speed
        onSpeedRequested(playbackParameters.speed)
        return if (phase == Phase.ACTIVE) super.handleSetPlaybackParameters(playbackParameters) else Futures.immediateVoidFuture()
    }

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        if (!onReceiver) return super.handleReplaceMediaItems(fromIndex, toIndex, mediaItems)
        // A receiver's queue is never edited: a replace would interrupt it. It is news for the way back, if it holds no receiver address.
        val c = cast
        val local = mediaItems.filterNot(ReceiverItems::isReceiverItem)
        if (c != null && local.size == mediaItems.size && local.isNotEmpty() && local.all { it.titleKey() == c.serverId to c.key } && toIndex <= c.local.size) {
            c.local = c.local.take(fromIndex) + local + c.local.drop(toIndex)
            if (phase != Phase.ACTIVE) invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        if (onReceiver) Futures.immediateVoidFuture() else super.handleAddMediaItems(index, mediaItems)

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        if (onReceiver) Futures.immediateVoidFuture() else super.handleMoveMediaItems(fromIndex, toIndex, newIndex)

    /** Removing everything is how the queue is emptied (a sign-out, a switch): the cast ends with it. */
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        if (!onReceiver) return super.handleRemoveMediaItems(fromIndex, toIndex)
        if (fromIndex == 0 && toIndex >= mediaItemCount) {
            cast = null
            device.clearMediaItems()
            endCast()
        }
        return Futures.immediateVoidFuture()
    }

    /** A stop ends the cast and its session now, so a sign-out behind it still has the token for the close. */
    override fun handleStop(): ListenableFuture<*> {
        if (!onReceiver) return super.handleStop()
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
        onCastSessionEnded()
        leave()
    }

    override fun handleRelease(): ListenableFuture<*> {
        generation++
        stopJobs()
        onCastSessionEnded()
        if (onReceiver) link.endSession(stopReceiver = true)
        return super.handleRelease()
    }

    private fun MediaItem.fileDurationMs(): Long? = mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS)?.takeIf { it > 0 }

    private companion object {
        const val TAG = "CastOutput"
        /** A receiver that has not started the title by then is not going to. */
        const val LOAD_TIMEOUT_MS = 45_000L
        const val TICK_MS = 1_000L
        /** Between "this phone" in the system's output switcher and the session's end, in whichever order they arrive. */
        const val TAKE_BACK_WINDOW_MS = 15_000L
        /** The same title set again at about the same position (new sources) is no seek. */
        const val SAME_POSITION_MS = 3_000L
        const val SPEED_SLACK = 0.001f

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
