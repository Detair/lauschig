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
 * Positions are within the current file; [stepBack] maps the rewind across file boundaries.
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
        return rewindMs(now - p.at, baseSec)
    }

    private companion object {
        const val POSITION_SLACK_MS = 1_000L
    }
}

/**
 * The position [backMs] before ([index], [positionMs]) in a multi-file book: crosses into earlier files using
 * [durationMs] (null = unknown, then it stops at the start of the file it reached); never before the book start.
 */
fun stepBack(index: Int, positionMs: Long, backMs: Long, durationMs: (Int) -> Long?): Pair<Int, Long> {
    var i = index
    var pos = positionMs.coerceAtLeast(0)
    var back = backMs.coerceAtLeast(0)
    while (back > pos) {
        if (i == 0) return 0 to 0L
        val prev = durationMs(i - 1) ?: return i to 0L
        back -= pos
        i--
        pos = prev
    }
    return i to pos - back
}
