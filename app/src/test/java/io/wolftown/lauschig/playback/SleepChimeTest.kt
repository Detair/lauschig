package io.wolftown.lauschig.playback

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SleepChimeTest {
    private val rate = 44_100
    private val pcm = SoftChime.pcm(rate)

    @Test fun short_underOneAndAHalfSeconds() {
        val sec = pcm.size.toDouble() / rate
        assertTrue("length $sec s", sec in 0.6..1.5)
    }

    @Test fun quiet_peakWellBelowFullScale_butAudible() {
        val peak = pcm.maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toDouble()
        assertTrue("peak $peak", peak in 0.1..0.3)
    }

    @Test fun noClick_startsAndEndsInSilence() {
        // The first half millisecond (the soft attack only begins) and the last 2 ms (faded out).
        val limit = Short.MAX_VALUE * 0.01
        assertTrue("start", pcm.take((rate * 0.0005).toInt()).all { abs(it.toInt()) < limit })
        assertTrue("end", pcm.takeLast((rate * 0.002).toInt()).all { abs(it.toInt()) < limit })
    }

    @Test fun softAttack_noJumpBetweenSamples() {
        // A bell-like tone moves smoothly: no step bigger than a sine at its loudest can make.
        val maxStep = (1 until pcm.size).maxOf { abs(pcm[it] - pcm[it - 1]) } / Short.MAX_VALUE.toDouble()
        assertTrue("step $maxStep", maxStep < 0.05)
    }
}
