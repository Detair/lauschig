package io.wolftown.lauschig.playback

import android.media.audiofx.LoudnessEnhancer
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
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
 * Service-side comfort features on the ExoPlayer itself, so every controller (app, notification, headset, Auto)
 * gets them: rewind on resume ([ResumeRewind]), speed per title ([SpeedMemory]), skip silence (global) and the
 * volume boost per title (a [LoudnessEnhancer] on the player's audio session). Main thread only.
 */
@OptIn(UnstableApi::class) // skip silence, audio session id
class PlaybackComfort(
    private val exo: ExoPlayer,
    private val titleAudio: TitleAudioDao,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private data class Key(val serverId: String, val itemId: String) {
        override fun toString() = "$serverId:$itemId"
    }

    private val rewind = ResumeRewind()
    private val speeds = SpeedMemory()
    private val boost = AudioBoost()
    private var key: Key? = null
    private var titleJob: Job? = null
    private var saveJob: Job? = null
    private var rewindSec = PlaybackSettings.DEFAULT_REWIND

    fun start() {
        exo.addListener(this)
        boost.attach(exo.audioSessionId)
        scope.launch { settings.rewindOnResumeSec.collect { rewindSec = it } }
        scope.launch { settings.skipSilence.collect { exo.skipSilenceEnabled = it } }
        onTitle(exo.currentMediaItem)
    }

    fun release() {
        exo.removeListener(this)
        titleJob?.cancel()
        boost.release()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onTitle(mediaItem)

    private fun onTitle(item: MediaItem?) {
        val k = item?.key()
        if (k == key) return
        key = k
        rewind.invalidate()
        speeds.beginTitle()
        titleJob?.cancel()
        if (k == null) return
        titleJob = scope.launch {
            var first = true
            combine(titleAudio.observe(k.serverId, k.itemId), settings.defaultSpeed, settings.defaultBoostDb) { row, speed, boostDb ->
                Triple(row, speed, boostDb)
            }.collect { (row, defaultSpeed, defaultBoost) ->
                if (first) {
                    first = false
                    exo.setPlaybackSpeed(speeds.onTitle(k.toString(), row?.speed, defaultSpeed))
                }
                boost.setGainDb(row?.boostDb ?: defaultBoost)
            }
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        if (speeds.onSpeedChanged(playbackParameters.speed) == null) return
        val k = key ?: return
        val speed = playbackParameters.speed
        // The speed slider changes it many times a second; store the value it settles on.
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            titleAudio.setSpeed(k.serverId, k.itemId, speed)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) resumed() else paused()
    }

    /** A call or navigation prompt pauses without touching playWhenReady; its end is a resume too. */
    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (!exo.playWhenReady) return
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) resumed() else paused()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) rewind.invalidate()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) = boost.attach(audioSessionId)

    private fun paused() {
        val k = key ?: return
        if (exo.playbackState == Player.STATE_ENDED || exo.mediaItemCount == 0) return
        rewind.onPaused(k.toString(), exo.currentMediaItemIndex, exo.currentPosition, clock())
    }

    private fun resumed() {
        val k = key ?: return
        val pos = exo.currentPosition
        val back = rewind.onResumed(k.toString(), exo.currentMediaItemIndex, pos, clock(), rewindSec)
        if (back > 0) {
            Log.i(TAG, "rewinding ${back}ms after the pause")
            exo.seekTo(exo.currentMediaItemIndex, pos - back)
        }
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
