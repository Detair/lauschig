package io.wolftown.lauschig.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** [PlayerFacade] over a [MediaController] connected to [PlaybackService]; connects lazily on first use. Main thread only. */
@Singleton
class MediaControllerFacade @Inject constructor(@ApplicationContext private val context: Context) : PlayerFacade {
    private var controller: MediaController? = null
    private var onChange: () -> Unit = {}
    private var onError: (Int) -> Unit = {}
    private var onDisconnected: () -> Unit = {}

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = onChange()
        override fun onPlayerError(error: PlaybackException) = onError(error.errorCode)
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@MediaControllerFacade.controller === controller) this@MediaControllerFacade.controller = null
            controller.release()
            onDisconnected()
        }
    }

    override suspend fun connect() {
        controller()
    }

    private suspend fun controller(): MediaController {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({
                try { cont.resume(future.get()) } catch (e: Exception) { cont.resumeWithException(e) }
            }, context.mainExecutor)
            cont.invokeOnCancellation { future.cancel(false) }
        }
        c.addListener(listener)
        controller = c
        return c
    }

    override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val c = controller()
        c.setMediaItems(items, startIndex, startPositionMs)
        c.prepare()
    }

    override suspend fun stop() {
        // A service started without the app (car, play key, widget) is reached by connecting; none running: nothing to stop.
        val c = controller ?: if (PlaybackService.running) controller() else return
        c.stop()
        c.clearMediaItems()
    }

    override fun replaceMediaItem(index: Int, item: MediaItem) { controller?.replaceMediaItem(index, item) }
    override fun play() { controller?.play() }
    override fun pause() { controller?.pause() }
    override fun seekTo(mediaIndex: Int, positionMs: Long) { controller?.seekTo(mediaIndex, positionMs) }
    override fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }
    override fun setVolume(volume: Float) { controller?.volume = volume.coerceIn(0f, 1f) }

    override fun snapshot(): PlayerSnapshot {
        val c = controller ?: return PlayerSnapshot(false, 0, 0, 1f)
        return PlayerSnapshot(
            c.isPlaying, c.currentMediaItemIndex, c.currentPosition.coerceAtLeast(0), c.playbackParameters.speed,
            isBuffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
        )
    }

    override fun currentKey(): Pair<String, String>? {
        val extras = controller?.currentMediaItem?.mediaMetadata?.extras ?: return null
        return (extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null) to (extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
    }

    override fun hasMedia() = controller?.let { it.isConnected && it.mediaItemCount > 0 } == true
    override fun setOnError(onError: (Int) -> Unit) { this.onError = onError }

    override fun streamBase(): String? {
        val c = controller ?: return null
        for (i in 0 until c.mediaItemCount) {
            c.getMediaItemAt(i).mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_STREAM_BASE)?.let { return it }
        }
        return null
    }
    override fun setOnDisconnected(onDisconnected: () -> Unit) { this.onDisconnected = onDisconnected }

    override fun setOnChange(onChange: () -> Unit) { this.onChange = onChange }
}
