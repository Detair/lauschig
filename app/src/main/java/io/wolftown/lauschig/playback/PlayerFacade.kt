package io.wolftown.lauschig.playback

import androidx.media3.common.MediaItem

data class PlayerSnapshot(val isPlaying: Boolean, val mediaIndex: Int, val positionMs: Long, val speed: Float)

/** The small slice of the player that [PlayerControllerImpl] needs; implemented over a MediaController, faked in tests. */
interface PlayerFacade {
    /** Replaces the queue, prepares and seeks; completes once the (possibly remote) player accepted it. */
    suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(mediaIndex: Int, positionMs: Long)
    fun setSpeed(speed: Float)
    fun snapshot(): PlayerSnapshot

    /** [onChange] is invoked on every relevant player event (play state, item, seek, speed). */
    fun setOnChange(onChange: () -> Unit)
}
