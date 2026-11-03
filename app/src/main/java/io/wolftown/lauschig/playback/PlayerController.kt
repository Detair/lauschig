package io.wolftown.lauschig.playback

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow

/**
 * Progress of a loading stream: [bytes] so far, [expectedBytes] and [etaSec] when known (the index of an m4b at the end
 * of the file), [bytesPerSec] over the last seconds.
 */
data class StreamLoad(val bytes: Long, val expectedBytes: Long?, val bytesPerSec: Long, val etaSec: Long?)

/** UI-facing playback state; positions are book-wide seconds. */
data class PlayerUiState(
    val itemId: String? = null,
    val title: String = "",
    val coverPath: String? = null,
    val isPlaying: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    /** Index into the item's chapters, -1 if it has none. */
    val chapterIndex: Int = -1,
    val chapterTitle: String? = null,
    val chapterStartSec: Double = 0.0,
    val chapterEndSec: Double = 0.0,
    /** Book-wide end of every chapter, in chapter order (for the sleep timer). */
    val chapterEndsSec: List<Double> = emptyList(),
    val speed: Float = 1f,
    /** True while at least one file of the loaded title comes from the server instead of the device. */
    val isStreaming: Boolean = false,
    /** A title is being prepared (details, connection) or the player is buffering; shown instead of play/pause. */
    val isLoading: Boolean = false,
    /** Only while [isLoading] and [isStreaming] and the stream is actually loading. */
    val streamLoad: StreamLoad? = null,
    /** A download is being slowed down so this stream keeps playing. */
    val downloadThrottled: Boolean = false,
    @StringRes val error: Int? = null,
)

interface PlayerController {
    val state: StateFlow<PlayerUiState>

    /** Starts [itemId]; start position is [startSec], else the saved progress (0 if finished/none). */
    fun play(serverId: String, itemId: String, startSec: Double? = null)
    fun togglePlayPause()

    /** Pauses if playing or waiting to play; never starts anything. */
    fun pause()
    fun seekBy(deltaSec: Int)

    /** Seeks to the absolute book-wide position [globalSec] (clamped to the book). */
    fun seekTo(globalSec: Double)

    /** Seeks the loaded title to [globalSec] and plays from there (history and bookmark taps). */
    fun seekToAndPlay(globalSec: Double) = seekTo(globalSec)
    fun seekToChapter(index: Int)
    fun setSpeed(speed: Float)

    /** Player volume 0..1 (sleep timer fade); not a setting, the service starts at 1. */
    fun setVolume(volume: Float) {}

    /** Re-reads the player right now instead of waiting for the next poll (sleep timer near its end). */
    fun refreshNow() {}
}
