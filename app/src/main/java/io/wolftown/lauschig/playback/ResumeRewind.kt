package io.wolftown.lauschig.playback

import kotlin.math.abs

/**
 * How far to jump back when resuming after a pause of [pauseMs], with the setting [baseSec] (0 = off):
 * under 3 s nothing (a double tap), under 10 min [baseSec], under 1 h twice, longer three times.
 */
fun rewindMs(pauseMs: Long, baseSec: Int): Long {
    if (baseSec <= 0 || pauseMs < 3_000) return 0
    val factor = when {
        pauseMs < 10 * 60_000L -> 1
        pauseMs < 60 * 60_000L -> 2
        else -> 3
    }
    return baseSec * factor * 1000L
}

/**
 * Remembers where and when playback paused. A resume of the same title at the same spot (same file, ±1 s) rewinds by
 * [rewindMs]; a seek or another title in between ([invalidate]) means the user chose the position, so nothing happens.
 * Positions are within the current file; the rewind never goes before its start.
 */
class ResumeRewind {
    private data class Paused(val key: String, val index: Int, val positionMs: Long, val at: Long)

    private var paused: Paused? = null

    /** A second pause report (e.g. focus loss after the user pause) keeps the first one. */
    fun onPaused(key: String, index: Int, positionMs: Long, now: Long) {
        if (paused == null) paused = Paused(key, index, positionMs, now)
    }

    fun invalidate() {
        paused = null
    }

    /** Ms to jump back now (0: none). Consumes the pause. */
    fun onResumed(key: String, index: Int, positionMs: Long, now: Long, baseSec: Int): Long {
        val p = paused ?: return 0
        paused = null
        if (p.key != key || p.index != index || abs(p.positionMs - positionMs) > POSITION_SLACK_MS) return 0
        return rewindMs(now - p.at, baseSec).coerceAtMost(positionMs.coerceAtLeast(0))
    }

    private companion object {
        const val POSITION_SLACK_MS = 1_000L
    }
}
