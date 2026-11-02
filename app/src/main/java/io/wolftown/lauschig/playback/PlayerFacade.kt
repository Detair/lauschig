package io.wolftown.lauschig.playback

import androidx.media3.common.MediaItem

/** [isBuffering]: the player wants to play but is waiting for data (stream start, seek into an unbuffered range). */
data class PlayerSnapshot(val isPlaying: Boolean, val mediaIndex: Int, val positionMs: Long, val speed: Float, val isBuffering: Boolean = false)

/** The small slice of the player that [PlayerControllerImpl] needs; implemented over a MediaController, faked in tests. */
interface PlayerFacade {
    /** Replaces the queue, prepares and seeks; completes once the (possibly remote) player accepted it. */
    suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    /** Swaps the queue entry at [index] (not the playing one) without interrupting playback. */
    fun replaceMediaItem(index: Int, item: MediaItem)
    fun play()
    fun pause()
    fun seekTo(mediaIndex: Int, positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float) {}
    fun snapshot(): PlayerSnapshot

    /** True while the (remote) player is connected and has a queue loaded. */
    fun hasMedia(): Boolean

    /** Invoked on a playback error (e.g. a file vanished). */
    fun setOnError(onError: () -> Unit)

    /** Invoked when the service connection was lost (service stopped); the queue is gone. */
    fun setOnDisconnected(onDisconnected: () -> Unit)

    /** [onChange] is invoked on every relevant player event (play state, item, seek, speed). */
    fun setOnChange(onChange: () -> Unit)
}
