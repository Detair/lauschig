package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeRewindTest {
    private val min = 60_000L

    @Test fun rewindMs_scalesWithThePauseLength() {
        assertEquals(0L, rewindMs(pauseMs = 2_999, baseSec = 5))
        assertEquals(5_000L, rewindMs(pauseMs = 3_000, baseSec = 5))
        assertEquals(5_000L, rewindMs(pauseMs = 10 * min - 1, baseSec = 5))
        assertEquals(10_000L, rewindMs(pauseMs = 10 * min, baseSec = 5))
        assertEquals(10_000L, rewindMs(pauseMs = 60 * min - 1, baseSec = 5))
        assertEquals(15_000L, rewindMs(pauseMs = 60 * min, baseSec = 5))
        assertEquals(30_000L, rewindMs(pauseMs = 24 * 60 * min, baseSec = 10))
    }

    @Test fun rewindMs_off() {
        assertEquals(0L, rewindMs(pauseMs = 60 * min, baseSec = 0))
    }

    @Test fun resumeAtThePausedPosition_rewinds() {
        val r = ResumeRewind()
        r.onPaused("b1", 0, 120_000, now = 0)
        assertEquals(5_000L, r.onResumed("b1", 0, 120_000, now = 60_000, baseSec = 5))
        // Consumed: the next resume without a pause does not rewind again.
        assertEquals(0L, r.onResumed("b1", 0, 115_000, now = 61_000, baseSec = 5))
    }

    @Test fun smallPositionDrift_stillRewinds_butASeekDoesNot() {
        val r = ResumeRewind()
        r.onPaused("b1", 0, 120_000, now = 0)
        assertEquals(5_000L, r.onResumed("b1", 0, 120_400, now = 60_000, baseSec = 5))
        r.onPaused("b1", 0, 120_000, now = 0)
        assertEquals(0L, r.onResumed("b1", 0, 150_000, now = 60_000, baseSec = 5))
    }

    @Test fun seekOrTitleChange_inBetween_disablesIt() {
        val r = ResumeRewind()
        r.onPaused("b1", 0, 120_000, now = 0)
        r.invalidate()
        assertEquals(0L, r.onResumed("b1", 0, 120_000, now = 60_000, baseSec = 5))
        r.onPaused("b1", 0, 120_000, now = 0)
        assertEquals(0L, r.onResumed("b2", 0, 120_000, now = 60_000, baseSec = 5))
        r.onPaused("b1", 0, 120_000, now = 0)
        assertEquals(0L, r.onResumed("b1", 1, 120_000, now = 60_000, baseSec = 5))
    }

    @Test fun repeatedPause_keepsTheFirstPauseTime() {
        val r = ResumeRewind()
        r.onPaused("b1", 0, 120_000, now = 0)
        r.onPaused("b1", 0, 120_000, now = 11 * min) // e.g. focus loss reported after the user pause
        assertEquals(10_000L, r.onResumed("b1", 0, 120_000, now = 12 * min, baseSec = 5))
    }

    @Test fun rewind_isClampedToTheStartOfTheFile() {
        val r = ResumeRewind()
        r.onPaused("b1", 2, 2_000, now = 0)
        assertEquals(2_000L, r.onResumed("b1", 2, 2_000, now = 60_000, baseSec = 5))
    }
}
