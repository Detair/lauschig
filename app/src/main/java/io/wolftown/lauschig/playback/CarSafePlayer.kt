package io.wolftown.lauschig.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.wolftown.lauschig.auto.CarControls

/**
 * The player the media session exposes. "Previous" / "next" (steering wheel, headset, Android Auto, lock screen) and
 * the rewind / fast-forward keys move by [stepMs] across file boundaries instead of ExoPlayer's defaults: on a one-file
 * m4b "previous" would jump to the start of the book and "next" would do nothing; on a multi-file book they would
 * skip whole files. Everything else, including the app's own seeks, passes through unchanged.
 */
@OptIn(UnstableApi::class)
class CarSafePlayer(private val player: Player, private val stepMs: Long) : ForwardingPlayer(player) {
    override fun seekToPrevious() = step(forward = false)
    override fun seekToPreviousMediaItem() = step(forward = false)
    override fun seekBack() = step(forward = false)
    override fun seekToNext() = step(forward = true)
    override fun seekToNextMediaItem() = step(forward = true)
    override fun seekForward() = step(forward = true)

    /** ±[stepMs] within the book (the car's ±30 s buttons call this too). */
    fun step(forward: Boolean) {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val (i, ms) = if (forward) CarControls.stepForward(index, position, stepMs, player.mediaItemCount, ::fileDurationMs)
        else CarControls.stepBack(index, position, stepMs, ::fileDurationMs)
        player.seekTo(i, ms)
    }

    private fun fileDurationMs(index: Int): Long? {
        if (index !in 0 until player.mediaItemCount) return null
        if (index == player.currentMediaItemIndex) player.duration.takeIf { it > 0 }?.let { return it }
        return player.getMediaItemAt(index).mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS)?.takeIf { it > 0 }
    }
}
