package io.wolftown.lauschig.playback

import io.wolftown.lauschig.diagnostics.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.wolftown.lauschig.db.dao.ItemWithDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feeds player events into the [ProgressTracker]. Events are processed strictly in order on [scope] (main).
 * The item/timeline for the tracker is resolved from the playing MediaItem's extras.
 *
 * When the queue is replaced (another title), ExoPlayer has already swapped the timeline before the pause /
 * transition callbacks run. The old title is therefore saved from the discontinuity's old position, and its
 * position lambda refuses to read a queue that belongs to another title (the tracker then keeps its last value).
 */
class PlaybackProgressBridge(
    private val player: Player,
    private val tracker: ProgressTracker,
    private val loadItem: suspend (serverId: String, itemId: String) -> ItemWithDetails?,
    scope: CoroutineScope,
) : Player.Listener {
    private data class Key(val serverId: String, val itemId: String)

    private class Ctx(val serverId: String, val itemId: String, val duration: Double, val mapper: TimelineMapper) {
        val key = Key(serverId, itemId)
    }

    private fun MediaItem.key(): Key? {
        val extras = mediaMetadata.extras ?: return null
        return Key(extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null, extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
    }

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var ctx: Ctx? = null

    init {
        scope.launch {
            for (job in queue) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One failed event must not stop progress tracking for the rest of the session.
                    Log.w(TAG, "progress event failed", e)
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) queue.trySend { startTracking() }
        else if (player.playbackState != Player.STATE_ENDED) queue.trySend { tracker.onPaused() }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // ENDED with an empty queue means it was cleared (a car, watch or Assistant controller): the removal already
        // saved the title where it was; only a queue played to its end finishes the title.
        if (playbackState == Player.STATE_ENDED && player.mediaItemCount > 0) queue.trySend { tracker.onEnded() }
    }

    // PositionInfo.mediaItem is the only way to identify the item of a queue that has already been replaced.
    @OptIn(UnstableApi::class)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) {
            // Only persist a paused seek for the title being tracked, never for a title whose queue was replaced.
            val seeked = newPosition.mediaItem?.key()
            queue.trySend { if (seeked != null && ctx?.key == seeked) tracker.checkpoint() }
            return
        }
        val old = oldPosition.mediaItem?.key() ?: return
        if (reason != Player.DISCONTINUITY_REASON_REMOVE && old == newPosition.mediaItem?.key()) return
        // The old queue is gone: save the old title at its own last position (no-op if it was already paused).
        val index = oldPosition.mediaItemIndex
        val offsetMs = oldPosition.positionMs
        queue.trySend {
            val c = ctx
            if (c != null && c.key == old) tracker.onPausedFor(c.serverId, c.itemId, c.mapper.toGlobal(index, offsetMs))
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val itemId = mediaItem?.mediaMetadata?.extras?.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return
        if (player.isPlaying && itemId != ctx?.itemId) queue.trySend { startTracking() }
    }

    /**
     * Current book-wide position, or null if nothing was tracked or the queue holds another title.
     * Call BEFORE releasing the player.
     */
    fun currentGlobalSec(): Double? {
        val c = ctx ?: return null
        if (player.currentMediaItem?.key() != c.key) return null
        return c.mapper.toGlobal(player.currentMediaItemIndex, player.currentPosition)
    }

    /**
     * Service teardown: stops the queue and saves [finalGlobalSec] (captured before release) on [saveScope],
     * which must outlive the service; the save is not cancellable.
     */
    fun shutdown(finalGlobalSec: Double?, saveScope: CoroutineScope) {
        queue.close()
        saveScope.launch { withContext(NonCancellable) { tracker.onPaused(finalGlobalSec) } }
    }

    private suspend fun startTracking() {
        val key = player.currentMediaItem?.key() ?: return
        var c = ctx
        if (c == null || c.key != key) {
            val item = loadItem(key.serverId, key.itemId) ?: return
            // The queue may have changed again while loading; the next event tracks the new one.
            if (player.currentMediaItem?.key() != key) return
            val mapper = TimelineMapper(item.audioFiles)
            c = Ctx(key.serverId, key.itemId, mapper.totalSec.takeIf { it > 0 } ?: item.item.durationSec, mapper).also { ctx = it }
        }
        val mapper = c.mapper
        tracker.onPlaying(key.serverId, key.itemId, c.duration) {
            // Never map another title's queue onto this title's timeline; the tracker falls back to its last position.
            check(player.currentMediaItem?.key() == key) { "queue holds another title" }
            mapper.toGlobal(player.currentMediaItemIndex, player.currentPosition)
        }
    }

    private companion object {
        const val TAG = "ProgressBridge"
    }
}
