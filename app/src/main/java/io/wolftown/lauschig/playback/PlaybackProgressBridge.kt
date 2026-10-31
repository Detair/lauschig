package io.wolftown.lauschig.playback

import androidx.media3.common.Player
import io.wolftown.lauschig.db.dao.ItemWithDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Feeds player events into the [ProgressTracker]. Events are processed strictly in order on [scope] (main).
 * The item/timeline for the tracker is resolved from the playing MediaItem's extras.
 */
class PlaybackProgressBridge(
    private val player: Player,
    private val tracker: ProgressTracker,
    private val loadItem: suspend (serverId: String, itemId: String) -> ItemWithDetails?,
    scope: CoroutineScope,
) : Player.Listener {
    private class Ctx(val serverId: String, val itemId: String, val duration: Double, val mapper: TimelineMapper)

    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var ctx: Ctx? = null

    init { scope.launch { for (job in queue) job() } }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) queue.trySend { startTracking() }
        else if (player.playbackState != Player.STATE_ENDED) queue.trySend { tracker.onPaused() }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) queue.trySend { tracker.onEnded() }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) queue.trySend { tracker.checkpoint() }
    }

    /** Service teardown: saves once more, then lets the queue drain and end. */
    fun shutdown() {
        queue.trySend { tracker.onPaused() }
        queue.close()
    }

    private suspend fun startTracking() {
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return
        val serverId = extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return
        val itemId = extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return
        var c = ctx
        if (c == null || c.itemId != itemId || c.serverId != serverId) {
            val item = loadItem(serverId, itemId) ?: return
            val mapper = TimelineMapper(item.audioFiles)
            c = Ctx(serverId, itemId, mapper.totalSec.takeIf { it > 0 } ?: item.item.durationSec, mapper).also { ctx = it }
        }
        val mapper = c.mapper
        tracker.onPlaying(serverId, itemId, c.duration) { mapper.toGlobal(player.currentMediaItemIndex, player.currentPosition) }
    }
}
