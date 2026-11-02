package io.wolftown.lauschig.playback

import androidx.media3.common.Player
import io.wolftown.lauschig.local.DeviceLibrary

/**
 * Starts a progress-only sync ([io.wolftown.lauschig.sync.SyncTrigger.syncProgress], debounced there) when playback is
 * paused (user, audio focus, headphones unplugged) or a title ends. A buffering stall is no pause. Device titles never
 * sync. [current] is the (serverId, playable key) of the player's current item.
 */
class ProgressSyncOnPause(
    private val current: () -> Pair<String, String>?,
    private val sync: () -> Unit,
) : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady) maybeSync()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) maybeSync()
    }

    private fun maybeSync() {
        val (serverId, key) = current() ?: return
        if (!DeviceLibrary.isLocal(serverId, key)) sync()
    }
}
