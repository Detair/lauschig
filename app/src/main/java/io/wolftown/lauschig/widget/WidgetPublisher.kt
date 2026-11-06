package io.wolftown.lauschig.widget

import io.wolftown.lauschig.diagnostics.Log
import androidx.media3.common.Player
import io.wolftown.lauschig.auto.CoverUris
import io.wolftown.lauschig.playback.MediaItemFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the widget in step with the service's player (main thread): title, chapter, play/pause and seeks go out at
 * once, the position while playing every [WidgetThrottle.INTERVAL_MS]. Writes are conflated and run on [io], so a
 * burst of player events costs one DataStore write and one widget redraw.
 */
class WidgetPublisher(
    /** The service's player as the widget needs it ([snapshot] of the player); read on the main thread. */
    private val snapshot: () -> WidgetPlayerSnapshot,
    private val hasItems: () -> Boolean,
    private val scope: CoroutineScope,
    /** Outlives [scope]: the last "paused" state is written after the service scope is gone. */
    private val io: CoroutineScope,
    private val store: WidgetStore,
    private val refresh: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : Player.Listener {
    private val throttle = WidgetThrottle()
    private var last: WidgetState? = null
    private var ticker: Job? = null
    private val pending = MutableStateFlow<WidgetState?>(null)
    private var writer: Job? = null

    /** Starts writing; the service adds this as a listener of its player. */
    fun start() {
        writer = io.launch {
            pending.filterNotNull().collect { write(it) }
        }
        scope.launch {
            // After a process death the stored state may still say "playing": the service starts with an empty player.
            last = try { store.get() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            publish()
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED,
            )
        ) changed()
    }

    /** Another account is active: the title kept for the empty player was the last one's, and must not be written back. */
    fun forgetTitle() {
        last = null
        publish()
    }

    /** The player changed (main thread). */
    fun changed() = publish()

    private fun publish() {
        val next = WidgetMapping.map(snapshot(), last)
        if (throttle.offer(next, clock())) {
            last = next
            pending.value = next
        }
        tick(next.playing)
    }

    private fun tick(playing: Boolean) {
        if (!playing) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(WidgetThrottle.INTERVAL_MS)
                publish()
            }
        }
    }

    private suspend fun write(state: WidgetState) {
        try {
            store.set(state)
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget state write failed", e)
        }
    }

    /** The service ends: the widget keeps the title, paused (main thread, before the player is released). */
    fun release() {
        ticker?.cancel()
        writer?.cancel()
        val final = (last ?: return).copy(playing = false).let { s ->
            if (hasItems()) WidgetMapping.map(snapshot(), last).copy(playing = false) else s
        }
        io.launch { write(final) }
    }

    companion object {
        private const val TAG = "WidgetPublisher"

        fun snapshot(p: Player): WidgetPlayerSnapshot {
            val item = p.currentMediaItem
            val md = item?.mediaMetadata
            val extras = md?.extras
            val durations = (0 until p.mediaItemCount).map { i ->
                p.getMediaItemAt(i).mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS) ?: 0L
            }
            return WidgetPlayerSnapshot(
                serverId = extras?.getString(MediaItemFactory.EXTRA_SERVER_ID),
                key = extras?.getString(MediaItemFactory.EXTRA_ITEM_ID),
                title = (md?.title ?: md?.displayTitle)?.toString(),
                subtitle = md?.subtitle?.toString(),
                artist = md?.artist?.toString(),
                coverVersion = md?.artworkUri?.let { CoverUris.parse(it)?.version } ?: 0,
                playWhenReady = p.playWhenReady,
                playbackState = p.playbackState,
                fileIndex = p.currentMediaItemIndex,
                positionInFileMs = p.currentPosition,
                fileDurationsMs = durations,
                currentFileDurationMs = p.duration,
            )
        }
    }
}
