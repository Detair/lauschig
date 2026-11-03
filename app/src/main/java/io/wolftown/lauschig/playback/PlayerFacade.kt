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

    /** (serverId, playable key) of the playing queue entry; the service may have loaded another title by itself. */
    fun currentKey(): Pair<String, String>? = null

    /** Connects to the service if not connected yet (a title may already be playing there). */
    suspend fun connect() {}

    /** Stops and empties the queue (sign-out); reaches a running service even if this facade was not connected yet. */
    suspend fun stop() {}

    /** True while the (remote) player is connected and has a queue loaded. */
    fun hasMedia(): Boolean

    /** Invoked on a playback error (e.g. a file vanished) with its `PlaybackException` error code. */
    fun setOnError(onError: (errorCode: Int) -> Unit)

    /** The server the queue's streamed files point at (`MediaItemFactory.EXTRA_STREAM_BASE`), null if nothing streams. */
    fun streamBase(): String? = null

    /** Invoked when the service connection was lost (service stopped); the queue is gone. */
    fun setOnDisconnected(onDisconnected: () -> Unit)

    /** [onChange] is invoked on every relevant player event (play state, item, seek, speed). */
    fun setOnChange(onChange: () -> Unit)
}
