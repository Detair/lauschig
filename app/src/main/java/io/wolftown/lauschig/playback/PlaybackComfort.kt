package io.wolftown.lauschig.playback

import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import io.wolftown.lauschig.diagnostics.Log
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.wolftown.lauschig.db.dao.TitleAudioDao
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Service-side comfort features in the service, so every controller (app, notification, headset, Auto) gets them.
 *
 * The title's parts follow the title wherever it plays, on [player]: rewind on resume ([ResumeRewind]), speed per title
 * ([SpeedMemory]) and the last activity. The sound's parts only exist where this device makes the sound, on [device]: skip
 * silence (global), the volume boost per title (a [LoudnessEnhancer] on its audio session) and the night compressor (global, a
 * [AudioCompressor] on the same session). A Cast receiver has none of them. Main thread only.
 */
@OptIn(UnstableApi::class) // skip silence, audio session id
class PlaybackComfort(
    private val player: Player,
    private val device: ExoPlayer,
    private val titleAudio: TitleAudioDao,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    /** Outlives the service: speed saves and the last-played record still land after release. */
    private val saveScope: CoroutineScope,
    /** The owner of the player's volume, shared with the session player (see [PlayerVolume]). */
    private val volume: PlayerVolume = PlayerVolume { device.volume = it },
    /** The title paused, stopped or started on this device (see [LastPlayedStore]). */
    private val onTitleActivity: (serverId: String, itemId: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private data class Key(val serverId: String, val itemId: String) {
        override fun toString() = "$serverId:$itemId"
    }

    private val rewind = ResumeRewind()
    private val speeds = SpeedMemory()
    private val boost = AudioBoost()
    private val compressor = AudioCompressor()
    private var key: Key? = null
    private var titleJob: Job? = null
    private var saveJob: Job? = null
    private var rewindSec = PlaybackSettings.DEFAULT_REWIND
    /** Cached so a new title gets the default speed synchronously, before its own row is read. */
    private var defaultSpeed = 1f
    private var pendingSpeed: Pair<Key, Float>? = null

    private val deviceListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            boost.attach(audioSessionId)
            compressor.attach(audioSessionId)
        }
    }

    private val onThisDevice: Boolean get() = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL

    fun start() {
        player.addListener(this)
        device.addListener(deviceListener)
        boost.attach(device.audioSessionId)
        compressor.attach(device.audioSessionId)
        // One switch at a time (collect is sequential); while playing it fades its own gain around the change (no click),
        // on top of whatever volume the sleep timer asks for.
        val quiet = QuietSwitch(gain = volume::setSwitchGain, playing = { QuietSwitch.fades(device.playWhenReady, device.playbackState) })
        scope.launch { settings.speechCompressor.collect { on -> quiet.run { compressor.setEnabled(on) } } }
        scope.launch { settings.rewindOnResumeSec.collect { rewindSec = it } }
        scope.launch { settings.skipSilence.collect { device.skipSilenceEnabled = it } }
        scope.launch { settings.defaultSpeed.collect { defaultSpeed = it } }
        onTitle(player.currentMediaItem)
    }

    fun release() {
        key?.let { if (player.mediaItemCount > 0) onTitleActivity(it.serverId, it.itemId) }
        player.removeListener(this)
        device.removeListener(deviceListener)
        titleJob?.cancel()
        flushSpeed()
        boost.release()
        compressor.release()
    }

    /** Writes a speed change that is still waiting for the slider to settle. */
    private fun flushSpeed() {
        saveJob?.cancel()
        val (k, speed) = pendingSpeed ?: return
        pendingSpeed = null
        saveScope.launch { titleAudio.setSpeed(k.serverId, k.itemId, speed) }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTitle(mediaItem)

    private fun onTitle(item: MediaItem?) {
        val k = item?.key()
        if (k == key) return
        flushSpeed() // a pending change belongs to the previous title
        key = k
        rewind.invalidate()
        speeds.beginTitle()
        titleJob?.cancel()
        if (k == null) return
        // Synchronously: never let the new title start at the previous title's speed. Its own speed follows.
        player.setPlaybackSpeed(speeds.onTitle(k.toString(), null, defaultSpeed))
        titleJob = scope.launch {
            var first = true
            combine(titleAudio.observe(k.serverId, k.itemId), settings.defaultSpeed, settings.defaultBoostDb) { row, speed, boostDb -> Triple(row, speed, boostDb) }
                .collect { (row, default, defaultBoost) ->
                    if (first) {
                        first = false
                        // The title's own speed, or the default if the cached one was not read yet.
                        val target = row?.speed ?: default
                        if (target != player.playbackParameters.speed) player.setPlaybackSpeed(speeds.onTitle(k.toString(), row?.speed, default))
                    }
                    boost.setGainDb(row?.boostDb ?: defaultBoost)
                }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        // What a receiver reports is its own: at most 2×, and 1× after every load. A person's speed arrives as [speedRequested].
        if (onThisDevice) speedChanged(playbackParameters.speed)
    }

    /** A person asked for [speed] while the title plays on a receiver: it is the title's, also where the receiver cannot follow. */
    fun speedRequested(speed: Float) = speedChanged(speed)

    private fun speedChanged(speed: Float) {
        if (speeds.onSpeedChanged(speed) == null) return
        val k = key ?: return
        // The speed slider changes it many times a second; store the value it settles on.
        pendingSpeed = k to speed
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            flushSpeed()
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) resumed() else paused()
    }

    /** A call or navigation prompt pauses without touching playWhenReady; its end is a resume too. */
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (!player.playWhenReady) return
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) resumed() else paused()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) rewind.invalidate()
    }

    /** The title moved to another output: that is no pause of the person's, and nothing rewinds for it. */
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = rewind.invalidate()

    private fun paused() {
        val k = key ?: return
        if (player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0) return
        rewind.onPaused(k.toString(), player.currentMediaItemIndex, player.currentPosition, clock())
        onTitleActivity(k.serverId, k.itemId)
    }

    private fun resumed() {
        val k = key ?: return
        val pos = player.currentPosition
        val back = rewind.onResumed(k.toString(), player.currentMediaItemIndex, pos, clock(), rewindSec)
        if (back > 0) {
            val (i, ms) = stepBack(player.currentMediaItemIndex, pos, back) { idx ->
                if (idx < player.mediaItemCount) player.getMediaItemAt(idx).mediaMetadata.extras?.getLong(MediaItemFactory.EXTRA_FILE_DURATION_MS)?.takeIf { it > 0 } else null
            }
            Log.i(TAG, "rewinding ${back}ms after the pause")
            player.seekTo(i, ms)
        }
        onTitleActivity(k.serverId, k.itemId)
    }

    private fun MediaItem.key(): Key? {
        val extras = mediaMetadata.extras ?: return null
        return Key(extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null, extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
    }

    private companion object {
        const val SAVE_DELAY_MS = 600L
        const val TAG = "PlaybackComfort"
    }
}

/** A [LoudnessEnhancer] on one audio session; 0 dB releases it. Failures (no effect on the device) only log. */
class AudioBoost {
    private var session = NO_SESSION
    private var gainDb = 0
    private var enhancer: LoudnessEnhancer? = null

    fun attach(sessionId: Int) {
        if (sessionId == session) return
        release()
        session = sessionId
        apply()
    }

    fun setGainDb(db: Int) {
        if (db == gainDb && (db == 0 || enhancer != null)) return
        gainDb = db
        apply()
    }

    private fun apply() {
        if (gainDb <= 0 || session == NO_SESSION) {
            enhancer?.enabled = false
            return
        }
        try {
            val e = enhancer ?: LoudnessEnhancer(session).also { enhancer = it }
            e.setTargetGain(gainDb * 100) // millibel
            e.enabled = true
        } catch (e: Exception) {
            Log.w("AudioBoost", "loudness enhancer unavailable", e)
            release()
        }
    }

    private companion object {
        /** Same value as Media3's unset session id (0). */
        const val NO_SESSION = 0
    }

    fun release() {
        try { enhancer?.release() } catch (e: Exception) { Log.w("AudioBoost", "release failed", e) }
        enhancer = null
    }
}
