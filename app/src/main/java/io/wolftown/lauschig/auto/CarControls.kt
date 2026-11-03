package io.wolftown.lauschig.auto

import io.wolftown.lauschig.playback.SleepMode

/**
 * The rules behind the car's buttons (pure). Every step is reversible: nothing jumps to the start of a book, and a
 * second tap undoes or continues the first one.
 */
object CarControls {
    /** The speed steps of the speed button; values set in the app snap to the next step up. */
    val SPEEDS = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    private const val EPSILON = 0.01f

    fun nextSpeed(current: Float): Float = SPEEDS.firstOrNull { it > current + EPSILON } ?: SPEEDS.first()

    /** Off → 15 → 30 → 60 min → end of chapter → off; a timer set in the app (other minutes) goes to end of chapter. */
    fun nextSleep(current: SleepMode?): SleepMode? = when (current) {
        null -> SleepMode.Minutes(15)
        SleepMode.Minutes(15) -> SleepMode.Minutes(30)
        SleepMode.Minutes(30) -> SleepMode.Minutes(60)
        is SleepMode.Minutes -> SleepMode.EndOfChapter
        SleepMode.EndOfChapter -> null
    }

    /**
     * The position [forwardMs] after ([index], [positionMs]) in a book of [count] files: crosses into later files using
     * [durationMs] (null = unknown: stays in the file and lets the player clamp); never past the end of the last file.
     */
    fun stepForward(index: Int, positionMs: Long, forwardMs: Long, count: Int, durationMs: (Int) -> Long?): Pair<Int, Long> {
        var i = index
        var target = positionMs.coerceAtLeast(0) + forwardMs.coerceAtLeast(0)
        while (true) {
            val length = durationMs(i) ?: return i to target
            if (target <= length) return i to target
            if (i >= count - 1) return i to length
            target -= length
            i++
        }
    }

    /** The resume-rewind rule: back across files, never before the start of the book. */
    fun stepBack(index: Int, positionMs: Long, backMs: Long, durationMs: (Int) -> Long?): Pair<Int, Long> =
        io.wolftown.lauschig.playback.stepBack(index, positionMs, backMs, durationMs)

    /** Within [RESTART_SEC] of a chapter start "back" means the previous chapter, else the start of this one. */
    const val RESTART_SEC = 3.0

    /** Book-wide target of "chapter back" for chapters starting at [starts] (ascending); null without chapters. */
    fun chapterBack(starts: List<Double>, positionSec: Double): Double? {
        if (starts.isEmpty()) return null
        val current = starts.indexOfLast { it <= positionSec }.coerceAtLeast(0)
        return if (positionSec - starts[current] > RESTART_SEC || current == 0) starts[current] else starts[current - 1]
    }

    /** Book-wide start of the next chapter; null in the last chapter or without chapters. */
    fun chapterNext(starts: List<Double>, positionSec: Double): Double? = starts.firstOrNull { it > positionSec + 0.001 }
}
