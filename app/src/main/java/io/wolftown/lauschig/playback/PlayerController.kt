package io.wolftown.lauschig.playback

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow

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
    val speed: Float = 1f,
    /** True while at least one file of the loaded title comes from the server instead of the device. */
    val isStreaming: Boolean = false,
    @StringRes val error: Int? = null,
)

interface PlayerController {
    val state: StateFlow<PlayerUiState>

    /** Starts [itemId]; start position is [startSec], else the saved progress (0 if finished/none). */
    fun play(serverId: String, itemId: String, startSec: Double? = null)
    fun togglePlayPause()
    fun seekBy(deltaSec: Int)

    /** Seeks to the absolute book-wide position [globalSec] (clamped to the book). */
    fun seekTo(globalSec: Double)
    fun seekToChapter(index: Int)
    fun setSpeed(speed: Float)
}
