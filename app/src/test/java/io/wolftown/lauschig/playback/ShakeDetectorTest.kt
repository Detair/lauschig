package io.wolftown.lauschig.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    private val g = 9.81f
    private val d = ShakeDetector(thresholdG = 2.5f, minHits = 3, windowMs = 1_000, cooldownMs = 2_000)

    @Test fun restingPhone_isNoShake() {
        for (t in 0L..2_000L step 20) assertFalse(d.onSample(0f, 0f, g, t))
    }

    @Test fun singleBump_isNoShake() {
        assertFalse(d.onSample(3 * g, 0f, g, 0))
        for (t in 20L..2_000L step 20) assertFalse(d.onSample(0f, 0f, g, t))
    }

    @Test fun severalStrongSamplesWithinTheWindow_areAShake_thenCooldown() {
        assertFalse(d.onSample(3 * g, 0f, 0f, 0))
        assertFalse(d.onSample(-3 * g, 0f, 0f, 200))
        assertTrue(d.onSample(3 * g, 0f, 0f, 400))
        // Still shaking right after: the cooldown keeps one shake from extending twice.
        assertFalse(d.onSample(3 * g, 0f, 0f, 600))
        assertFalse(d.onSample(3 * g, 0f, 0f, 800))
        assertFalse(d.onSample(3 * g, 0f, 0f, 1_000))
        assertFalse(d.onSample(3 * g, 0f, 0f, 2_500))
        assertFalse(d.onSample(3 * g, 0f, 0f, 2_700))
        assertTrue(d.onSample(3 * g, 0f, 0f, 2_900))
    }

    @Test fun strongSamplesTooFarApart_areNoShake() {
        assertFalse(d.onSample(3 * g, 0f, 0f, 0))
        assertFalse(d.onSample(3 * g, 0f, 0f, 900))
        assertFalse(d.onSample(3 * g, 0f, 0f, 1_800))
        assertFalse(d.onSample(3 * g, 0f, 0f, 2_700))
    }
}
