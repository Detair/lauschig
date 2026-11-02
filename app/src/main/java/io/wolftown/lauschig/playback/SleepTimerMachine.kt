package io.wolftown.lauschig.playback

/** What the sleep timer waits for. */
sealed interface SleepMode {
    data class Minutes(val minutes: Int) : SleepMode
    /** End of the current chapter (end of the title without chapters). */
    data object EndOfChapter : SleepMode
}

/** The player as the timer sees it; positions are book-wide seconds, [chapterEndsSec] indexed like the chapters. */
data class SleepInput(
    val isPlaying: Boolean,
    val positionSec: Double,
    val speed: Float = 1f,
    val chapterIndex: Int = -1,
    val chapterEndsSec: List<Double> = emptyList(),
    val durationSec: Double = 0.0,
)

/** [volume] for the player now; [pause]: the timer ran out (pause first, then restore the volume). */
data class SleepEffect(val volume: Float, val pause: Boolean)

/**
 * Pure sleep timer state. The glue ([SleepTimer]) feeds it the player state every few hundred ms.
 *
 * - [SleepMode.Minutes] counts listening time: a manual pause holds the timer.
 * - [SleepMode.EndOfChapter] ends after the chapter that was current at the start; jumping past it by hand moves
 *   the end to the new chapter, playing across it ends the timer.
 * - The last [fadeMs] fade out with `(remaining / fade)²`; a shake within the last [shakeWindowMs] extends by the
 *   chosen minutes, or by one chapter.
 */
class SleepTimerMachine(
    val fadeMs: Long = FADE_MS,
    val shakeWindowMs: Long = SHAKE_WINDOW_MS,
    /** Chapter mode ends this close to the chapter end (ticks are not exact). */
    private val endSlackMs: Long = 300,
) {
    var mode: SleepMode? = null
        private set
    private var remaining = 0L
    private var endIndex = -1
    private var lastPos = 0.0

    val isActive get() = mode != null

    fun start(mode: SleepMode, input: SleepInput) {
        this.mode = mode
        when (mode) {
            is SleepMode.Minutes -> remaining = mode.minutes * 60_000L
            SleepMode.EndOfChapter -> endIndex = if (input.chapterEndsSec.isEmpty()) -1 else input.chapterIndex.coerceIn(0, input.chapterEndsSec.lastIndex)
        }
        lastPos = input.positionSec
    }

    fun cancel() {
        mode = null
    }

    /** Listening time left in wall-clock ms, null when off. */
    fun remainingMs(input: SleepInput): Long? = when (mode) {
        null -> null
        is SleepMode.Minutes -> remaining
        SleepMode.EndOfChapter -> chapterRemainingMs(input)
    }

    fun inShakeWindow(input: SleepInput): Boolean = remainingMs(input)?.let { it <= shakeWindowMs } == true

    fun tick(input: SleepInput, deltaMs: Long): SleepEffect {
        val m = mode ?: return SleepEffect(1f, pause = false)
        var crossed = false
        when (m) {
            is SleepMode.Minutes -> if (input.isPlaying) remaining = (remaining - deltaMs).coerceAtLeast(0)
            SleepMode.EndOfChapter -> if (endIndex >= 0 && input.chapterIndex > endIndex) {
                val end = input.chapterEndsSec[endIndex.coerceAtMost(input.chapterEndsSec.lastIndex)]
                // Close to the end at the last tick: playback ran across it. Otherwise the user jumped there.
                val reach = deltaMs / 1000.0 * input.speed + CROSS_SLACK_SEC
                if (lastPos <= end && end - lastPos <= reach) crossed = true else endIndex = input.chapterIndex
            }
        }
        lastPos = input.positionSec
        val left = remainingMs(input) ?: 0
        val done = crossed || when (m) {
            is SleepMode.Minutes -> left <= 0
            SleepMode.EndOfChapter -> left <= endSlackMs
        }
        if (done) {
            mode = null
            return SleepEffect(1f, pause = true)
        }
        return SleepEffect(volumeFor(left), pause = false)
    }

    /** Extends the timer if it is in its last minute; true if it did. */
    fun shake(input: SleepInput): Boolean {
        val m = mode ?: return false
        if (!inShakeWindow(input)) return false
        return when (m) {
            is SleepMode.Minutes -> { remaining += m.minutes * 60_000L; true }
            SleepMode.EndOfChapter -> if (endIndex >= 0 && endIndex + 1 < input.chapterEndsSec.size) { endIndex++; true } else false
        }
    }

    fun volumeFor(leftMs: Long): Float {
        if (leftMs >= fadeMs) return 1f
        val f = (leftMs.coerceAtLeast(0).toFloat() / fadeMs)
        return f * f
    }

    private fun chapterRemainingMs(input: SleepInput): Long {
        val end = if (endIndex < 0 || input.chapterEndsSec.isEmpty()) input.durationSec
        else input.chapterEndsSec[endIndex.coerceAtMost(input.chapterEndsSec.lastIndex)]
        val speed = input.speed.takeIf { it > 0f } ?: 1f
        return ((end - input.positionSec) / speed * 1000).toLong().coerceAtLeast(0)
    }

    companion object {
        const val FADE_MS = 10_000L
        const val SHAKE_WINDOW_MS = 60_000L
        private const val CROSS_SLACK_SEC = 1.5
        val DURATIONS_MIN = listOf(5, 10, 15, 30, 45, 60, 90)
    }
}
