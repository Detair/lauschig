package io.wolftown.lauschig.playback

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import androidx.media3.common.Player
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
        val dp = setUpOrRelease(DynamicsProcessing(0, session, config), DynamicsProcessing::release) { it.enabled = false }
        return object : SessionEffect {
            override fun setEnabled(on: Boolean) { dp.enabled = on }
            override fun release() = dp.release()
        }
    }
}

/** Runs [setUp] on a freshly created [resource]; if that throws, the resource is released before the error goes on. */
internal inline fun <T> setUpOrRelease(resource: T, release: (T) -> Unit, setUp: (T) -> Unit): T {
    try {
        setUp(resource)
    } catch (e: Throwable) {
        try { release(resource) } catch (r: Exception) { e.addSuppressed(r) }
        throw e
    }
    return resource
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
            // Held before it is switched on: an effect that refuses is released below, never leaked.
            val e = create(session)
            effect = e
            e.setEnabled(true)
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
 * The one writer of the player's volume: what the controllers ask for ([request]: the sleep timer's fade, through the
 * session) times the gain of an effect switch ([setSwitchGain], see [QuietSwitch]). Neither side ever writes an absolute
 * volume over the other, so a switch during a sleep fade (or a fade during a switch) always ends at the timer's volume.
 * Main thread only.
 */
class PlayerVolume(private val apply: (Float) -> Unit) {
    /** The volume the controllers asked for last. */
    var requested = 1f
        private set
    private var switchGain = 1f

    fun request(volume: Float) {
        requested = volume.coerceIn(0f, 1f)
        push()
    }

    fun setSwitchGain(gain: Float) {
        switchGain = gain.coerceIn(0f, 1f)
        push()
    }

    private fun push() = apply(requested * switchGain)
}

/**
 * Changes the effect chain of a playing session without a click: its own [gain] (a factor on the player's volume, see
 * [PlayerVolume]) fades out in [steps] over `steps × stepMs`, the change happens in silence, [settleMs] let the effect's
 * buffers fill with silence, then the gain fades back in. Whatever volume the sleep timer sets meanwhile stays its own.
 * Not [playing] (paused, ended) the change just happens. Used for the night compressor, whose insertion would otherwise
 * cut into the running audio. Main thread only; callers run one switch at a time.
 */
class QuietSwitch(
    private val gain: (Float) -> Unit,
    private val playing: () -> Boolean,
    private val steps: Int = 5,
    private val stepMs: Long = 12,
    private val settleMs: Long = 40,
) {
    suspend fun run(change: () -> Unit) {
        if (!playing()) {
            change()
            return
        }
        var changed = false
        try {
            for (i in steps - 1 downTo 0) {
                gain(i.toFloat() / steps)
                delay(stepMs)
            }
            change()
            changed = true
            delay(settleMs)
            for (i in 1..steps) {
                gain(i.toFloat() / steps)
                if (i < steps) delay(stepMs)
            }
        } catch (e: CancellationException) {
            // Never leave the player quiet (or the change undone).
            if (!changed) change()
            gain(1f)
            throw e
        }
    }

    companion object {
        /** Audio flows or is about to (buffering counts): the switch fades. */
        fun fades(playWhenReady: Boolean, playbackState: Int): Boolean =
            playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
    }
}
