package io.wolftown.lauschig.playback

import kotlin.math.sqrt

/**
 * Pure shake detection on accelerometer samples (m/s², gravity included): [minHits] samples above [thresholdG]
 * within [windowMs] are a shake; afterwards [cooldownMs] pass before the next one counts.
 */
class ShakeDetector(
    private val thresholdG: Float = 2.5f,
    private val minHits: Int = 3,
    private val windowMs: Long = 1_000,
    private val cooldownMs: Long = 2_000,
) {
    private val hits = ArrayDeque<Long>()
    private var lastShake = Long.MIN_VALUE

    fun onSample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        if (lastShake != Long.MIN_VALUE && timeMs - lastShake < cooldownMs) return false
        val g = sqrt(x * x + y * y + z * z) / GRAVITY
        if (g < thresholdG) return false
        hits.addLast(timeMs)
        while (hits.isNotEmpty() && timeMs - hits.first() > windowMs) hits.removeFirst()
        if (hits.size < minHits) return false
        hits.clear()
        lastShake = timeMs
        return true
    }

    private companion object {
        const val GRAVITY = 9.81f
    }
}
