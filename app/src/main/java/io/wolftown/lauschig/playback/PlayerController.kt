package io.wolftown.lauschig.playback

import androidx.annotation.StringRes
import io.wolftown.lauschig.cast.CastActivity
import io.wolftown.lauschig.settings.PlaybackSettings
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
    /** The seek steps of the settings (back / forward buttons), whatever is loaded. */
    val seekBackSec: Int = PlaybackSettings.DEFAULT_SEEK_BACK,
    val seekForwardSec: Int = PlaybackSettings.DEFAULT_SEEK_FORWARD,
    /** The Cast device that plays; null while the sound comes from this device. */
    val outputName: String? = null,
    /** What [outputName] is doing, for the line under the title. */
    val outputActivity: CastActivity = CastActivity.PLAYING,
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

    /** The service started a title by itself (car, resumption, queue): show it. */
    fun adoptFromService() {}

    /**
     * Shows [itemId] of [serverId] as the loaded title, paused at its saved position, without touching the player: the
     * mini player has it, and the play button starts it like any title. Back on a profile, its last title waits like this.
     * Nothing ever starts by itself. Something loaded in the player stays and this does nothing.
     */
    fun showPaused(serverId: String, itemId: String) {}

    /**
     * Sign-out: stops playback entirely (a device title too) and empties the queue; the loaded and the last title are
     * forgotten, so play/pause cannot start them again. Returns once the player is empty.
     */
    suspend fun stop() {}
}
