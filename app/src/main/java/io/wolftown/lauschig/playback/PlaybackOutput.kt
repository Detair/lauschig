package io.wolftown.lauschig.playback

import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Where the service's titles play – the seam for Google Cast (Phase 6 preparation, Phase 7 feature).
 *
 * [player] is what plays: the media session (and through it the app's `PlayerController`, notification, lock screen,
 * headset, car), the progress tracking, the queue, the chapters, the widget and the play-key guard are built on it.
 * [device] is this device's ExoPlayer: its audio session carries the effects (boost, compressor), it skips silence and
 * the stream monitor watches its buffer – all of which only matter while the sound comes from here.
 *
 * Without Google Play services both are the same ExoPlayer ([DeviceOutput]). With them [player] plays here or on a Cast
 * receiver ([io.wolftown.lauschig.cast.CastOutput]) and moves the title between them; what is built on it follows it.
 */
interface PlaybackOutput {
    val player: Player
    val device: ExoPlayer

    /** True while the sound comes out of this device ([Player.getDeviceInfo]: local playback). */
    val isLocal: Boolean get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL

    /** The sound's volume where it comes out now (the sleep timer's fade, see [PlayerVolume]). */
    fun setVolume(volume: Float) {
        device.volume = volume
    }

    /** Releases the players (the service is going away). */
    fun release()
}

/** Playing on this device only: the ExoPlayer is the player. */
class DeviceOutput(override val device: ExoPlayer) : PlaybackOutput {
    override val player: Player get() = device

    override fun release() = device.release()
}
