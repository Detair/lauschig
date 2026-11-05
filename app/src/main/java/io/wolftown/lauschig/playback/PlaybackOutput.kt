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
 * Today both are the same ExoPlayer ([DeviceOutput]). Phase 7 can make [player] Media3's `CastPlayer`
 * (media3-cast ≥ 1.9: `CastPlayer.Builder(context).setLocalPlayer(device)`), which plays here or on a receiver and
 * moves the queue between them; what is built on [player] then follows it.
 */
interface PlaybackOutput {
    val player: Player
    val device: ExoPlayer

    /** True while the sound comes out of this device ([Player.getDeviceInfo]: local playback). */
    val isLocal: Boolean get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL

    /** Releases the players (the service is going away). */
    fun release()
}

/** Playing on this device only: the ExoPlayer is the player. */
class DeviceOutput(override val device: ExoPlayer) : PlaybackOutput {
    override val player: Player get() = device

    override fun release() = device.release()
}
