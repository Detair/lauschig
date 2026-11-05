package io.wolftown.lauschig.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import io.wolftown.lauschig.diagnostics.Log
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** The sleep timer's hint tone, 30 s before it ends ([SleepEffect.playChime]). */
fun interface SleepChime {
    fun play()
}

/**
 * A soft two-note chime (G5, then E5, bell-like decay), synthesised: no asset, no system beep. Quiet (a fifth of full
 * scale), raised-cosine attack and a faded end, so it never clicks.
 */
object SoftChime {
    const val SAMPLE_RATE = 44_100
    const val LENGTH_SEC = 1.0
    private val NOTES = listOf(0.0 to 784.0, 0.28 to 659.25) // start (s) to frequency (Hz)
    private const val PEAK = 0.2
    private const val ATTACK_SEC = 0.008
    private const val DECAY_SEC = 0.25
    private const val FADE_OUT_SEC = 0.06

    fun pcm(rate: Int = SAMPLE_RATE): ShortArray {
        val n = (rate * LENGTH_SEC).toInt()
        val raw = DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            NOTES.sumOf { (start, f) -> note(t - start, f) }
        }
        val fade = (rate * FADE_OUT_SEC).toInt()
        for (i in 0 until fade) raw[n - 1 - i] *= i.toDouble() / fade
        val peak = raw.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(n) { (raw[it] / peak * PEAK * Short.MAX_VALUE).roundToInt().toShort() }
    }

    private fun note(t: Double, f: Double): Double {
        if (t < 0) return 0.0
        val attack = if (t < ATTACK_SEC) 0.5 - 0.5 * cos(PI * t / ATTACK_SEC) else 1.0
        return attack * exp(-t / DECAY_SEC) * (sin(2 * PI * f * t) + 0.15 * sin(4 * PI * f * t))
    }
}

/**
 * Plays [SoftChime] as media (it follows the media volume and goes where the book goes: speaker, headphones, car). It
 * asks for no audio focus, so the book plays on under it. Failures (no audio device) only log.
 */
class AudioTrackChime @Inject constructor() : SleepChime {
    private val pcm by lazy { SoftChime.pcm() }

    override fun play() {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SoftChime.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            try {
                val written = track.write(pcm, 0, pcm.size)
                if (written != pcm.size) Log.w(TAG, "sleep chime: wrote $written of ${pcm.size} samples")
                if (!playable(written, pcm.size)) {
                    release(track)
                    return
                }
                track.play()
                Log.i(TAG, "sleep timer hint tone")
                Handler(Looper.getMainLooper()).postDelayed({ release(track) }, (SoftChime.LENGTH_SEC * 1000).toLong() + RELEASE_MARGIN_MS)
            } catch (e: Exception) {
                release(track)
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "sleep chime failed", e)
        }
    }

    private fun release(track: AudioTrack) {
        try { track.release() } catch (e: Exception) { Log.w(TAG, "release failed", e) }
    }

    companion object {
        private const val TAG = "SleepChime"
        private const val RELEASE_MARGIN_MS = 300L

        /** What `AudioTrack.write` returned is worth playing: some samples (a short write still sounds), not an error code. */
        fun playable(written: Int, expected: Int): Boolean = written in 1..expected
    }
}
