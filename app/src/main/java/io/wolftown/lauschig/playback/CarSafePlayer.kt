package io.wolftown.lauschig.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import io.wolftown.lauschig.auto.CarControls

/**
 * The player the media session exposes. "Previous" / "next" (steering wheel, headset, Android Auto, lock screen) and
 * the rewind / fast-forward keys move back by [backMs] and forward by [forwardMs] across file boundaries instead of
 * ExoPlayer's defaults: on a one-file m4b "previous" would jump to the start of the book and "next" would do nothing; on
 * a multi-file book they would skip whole files. Everything else, including the app's own seeks, passes through
 * unchanged. The steps follow the settings ([setSteps]); ExoPlayer's own increments are fixed at build time.
 *
 * Media3 only forwards "next" when the player offers it, and ExoPlayer does not without a next file (a one-file book,
 * the last file). So while a title is loaded the next/previous commands are always offered, also to listeners (the
 * session reads the commands from `onAvailableCommandsChanged`).
 */
@OptIn(UnstableApi::class)
class CarSafePlayer(private val player: Player, backMs: Long, forwardMs: Long) : ForwardingPlayer(player) {
    private val wrappers = HashMap<Player.Listener, Player.Listener>()
    private var backMs = backMs
    private var forwardMs = forwardMs

    override fun getSeekBackIncrement(): Long = backMs
    override fun getSeekForwardIncrement(): Long = forwardMs

    /** New step lengths (settings changed); the session's listeners hear the new increments. Main thread. */
    fun setSteps(backMs: Long, forwardMs: Long) {
        if (backMs != this.backMs) {
            this.backMs = backMs
            wrappers.keys.toList().forEach { it.onSeekBackIncrementChanged(backMs) }
        }
        if (forwardMs != this.forwardMs) {
            this.forwardMs = forwardMs
            wrappers.keys.toList().forEach { it.onSeekForwardIncrementChanged(forwardMs) }
        }
    }

    override fun seekToPrevious() = step(forward = false)
    override fun seekToPreviousMediaItem() = step(forward = false)
    override fun seekBack() = step(forward = false)
    override fun seekToNext() = step(forward = true)
    override fun seekToNextMediaItem() = step(forward = true)
    override fun seekForward() = step(forward = true)

    override fun getAvailableCommands(): Player.Commands = widen(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean =
        super.isCommandAvailable(command) || (command in STEP_COMMANDS && player.mediaItemCount > 0)

    override fun addListener(listener: Player.Listener) {
        val wrapper = wrappers.getOrPut(listener) { WidenedListener(listener, ::widen) }
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrappers.remove(listener) ?: listener)
    }

    private fun widen(commands: Player.Commands): Player.Commands =
        if (player.mediaItemCount == 0) commands else commands.buildUpon().addAll(*STEP_COMMANDS.toIntArray()).build()

    /** Back by [backMs] or forward by [forwardMs] within the book (the car's seek buttons call this too). */
    fun step(forward: Boolean) {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val (i, ms) = if (forward) CarControls.stepForward(index, position, forwardMs, player.mediaItemCount, ::fileDurationMs)
        else CarControls.stepBack(index, position, backMs, ::fileDurationMs)
        player.seekTo(i, ms)
    }

    private fun fileDurationMs(index: Int): Long? {
        if (index !in 0 until player.mediaItemCount) return null
        if (index == player.currentMediaItemIndex) player.duration.takeIf { it > 0 }?.let { return it }
        return player.getMediaItemAt(index).mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS)?.takeIf { it > 0 }
    }

    private companion object {
        val STEP_COMMANDS = listOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}

/**
 * Forwards every callback to [listener], with [widen] applied to the available commands. Written out on purpose: Kotlin
 * `by` delegation does not forward Java default methods, which would silently drop every other player event.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class WidenedListener(private val listener: Player.Listener, private val widen: (Player.Commands) -> Player.Commands) : Player.Listener {
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(widen(availableCommands))
    override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = listener.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
    override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)
    override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)
    override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)
    override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) = listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)
    override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()
    override fun onCues(cues: List<Cue>) = listener.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)

    // Same identity semantics as the wrapped listener (ForwardingPlayer compares listeners on removal).
    override fun equals(other: Any?) = other is WidenedListener && other.listener == listener
    override fun hashCode() = listener.hashCode()
}
