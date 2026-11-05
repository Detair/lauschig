package io.wolftown.lauschig.playback

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The night compressor ("Sprachfokus & Nacht-Kompression") as a [DynamicsProcessing] on the player's audio session:
 * one band over the whole spectrum that turns loud passages (music, effects) down gently, make-up gain that lifts quiet
 * speech, and a limiter against peaks. Values of a speech compressor for listening in bed; see the Phase 6 notes.
 */
object NightCompressor {
    const val BANDS = 1
    /** Session effects run on the mixed output of the session: stereo. */
    const val CHANNELS = 2
    const val THRESHOLD_DB = -24f
    const val RATIO = 3f
    const val KNEE_DB = 8f
    const val ATTACK_MS = 15f
    const val RELEASE_MS = 200f
    const val MAKE_UP_DB = 6f
    const val LIMITER_THRESHOLD_DB = -3f
    const val LIMITER_RATIO = 10f
    const val LIMITER_ATTACK_MS = 1f
    const val LIMITER_RELEASE_MS = 50f

    /** A disabled compressor on [session]; throws where the device has no dynamics processing. */
    fun create(session: Int): SessionEffect {
        val band = DynamicsProcessing.MbcBand(
            /* enabled = */ true, /* cutoffFrequency = */ 20_000f, ATTACK_MS, RELEASE_MS, RATIO, THRESHOLD_DB, KNEE_DB,
            /* noiseGateThreshold = */ -90f, /* expanderRatio = */ 1f, /* preGain = */ 0f, MAKE_UP_DB,
        )
        val limiter = DynamicsProcessing.Limiter(
            /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0, LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO, LIMITER_THRESHOLD_DB,
            /* postGain = */ 0f,
        )
        val config = DynamicsProcessing.Config.Builder(
            // The only variant AOSP's engine takes (the emulator refuses VARIANT_FAVOR_TIME_RESOLUTION).
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
            /* preEqInUse = */ false, 0, /* mbcInUse = */ true, BANDS, /* postEqInUse = */ false, 0, /* limiterInUse = */ true,
        )
            .setMbcAllChannelsTo(DynamicsProcessing.Mbc(/* inUse = */ true, /* enabled = */ true, BANDS).apply { setBand(0, band) })
            .setLimiterAllChannelsTo(limiter)
            .build()
        val dp = DynamicsProcessing(0, session, config).apply { enabled = false }
        return object : SessionEffect {
            override fun setEnabled(on: Boolean) { dp.enabled = on }
            override fun release() = dp.release()
        }
    }
}

/** An audio effect on one session, as [AudioCompressor] drives it. */
interface SessionEffect {
    fun setEnabled(on: Boolean)
    fun release()
}

/**
 * The night compressor on one audio session, like [AudioBoost]: on while [setEnabled] and a session is attached; off
 * it is released, so nothing processes the audio. A new session moves it. Failures (no effect on the device) only log.
 * Main thread only.
 */
class AudioCompressor(private val create: (session: Int) -> SessionEffect = NightCompressor::create) {
    private var session = NO_SESSION
    private var wanted = false
    private var effect: SessionEffect? = null

    fun attach(sessionId: Int) {
        if (sessionId == session) return
        release()
        session = sessionId
        apply()
    }

    fun setEnabled(on: Boolean) {
        if (on == wanted) return
        wanted = on
        apply()
    }

    private fun apply() {
        if (!wanted || session == NO_SESSION) {
            release()
            return
        }
        if (effect != null) return
        try {
            effect = create(session).also { it.setEnabled(true) }
        } catch (e: Exception) {
            Log.w(TAG, "dynamics processing unavailable", e)
            release()
        }
    }

    fun release() {
        try { effect?.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
        effect = null
    }

    private companion object {
        const val TAG = "AudioCompressor"
        /** Same value as Media3's unset session id (0). */
        const val NO_SESSION = 0
    }
}

/**
 * Changes the effect chain of a playing session without a click: the player's volume fades out in [steps] over
 * `steps × stepMs`, the change happens in silence, [settleMs] let the effect's buffers fill with silence, then the volume
 * fades back to where it was. Paused (or already silent) the change just happens. Used for the night compressor, whose
 * insertion would otherwise cut into the running audio. Main thread only; callers run one switch at a time.
 */
class QuietSwitch(
    private val volume: () -> Float,
    private val setVolume: (Float) -> Unit,
    private val playing: () -> Boolean,
    private val steps: Int = 5,
    private val stepMs: Long = 12,
    private val settleMs: Long = 40,
) {
    suspend fun run(change: () -> Unit) {
        val before = volume()
        if (!playing() || before <= 0f) {
            change()
            return
        }
        var changed = false
        try {
            for (i in steps - 1 downTo 0) {
                setVolume(before * i / steps)
                delay(stepMs)
            }
            change()
            changed = true
            delay(settleMs)
            for (i in 1..steps) {
                setVolume(before * i / steps)
                if (i < steps) delay(stepMs)
            }
        } catch (e: CancellationException) {
            // Never leave the player quiet (or the change undone).
            if (!changed) change()
            setVolume(before)
            throw e
        }
    }
}
