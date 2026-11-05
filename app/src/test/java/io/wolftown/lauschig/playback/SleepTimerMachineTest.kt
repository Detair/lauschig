package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerMachineTest {
    private val m = SleepTimerMachine(fadeMs = 10_000, shakeWindowMs = 60_000)
    private fun playing(pos: Double = 0.0, chapter: Int = -1, ends: List<Double> = emptyList(), speed: Float = 1f, duration: Double = 3600.0) =
        SleepInput(isPlaying = true, positionSec = pos, speed = speed, chapterIndex = chapter, chapterEndsSec = ends, durationSec = duration)

    @Test fun minutes_countDownOnlyWhilePlaying() {
        m.start(SleepMode.Minutes(5), playing())
        assertEquals(300_000L, m.remainingMs(playing()))
        m.tick(playing(), 60_000)
        assertEquals(240_000L, m.remainingMs(playing()))
        m.tick(playing().copy(isPlaying = false), 60_000)
        assertEquals(240_000L, m.remainingMs(playing()))
    }

    @Test fun off_hasNoRemainingAndFullVolume() {
        assertNull(m.remainingMs(playing()))
        assertEquals(SleepEffect(1f, pause = false), m.tick(playing(), 1_000))
        assertFalse(m.isActive)
    }

    @Test fun fade_fallsQuadraticallyOverTheLastTenSeconds() {
        m.start(SleepMode.Minutes(1), playing())
        assertEquals(1f, m.tick(playing(), 40_000).volume, 0f) // 20 s left
        assertEquals(1f, m.tick(playing(), 10_000).volume, 0f) // 10 s left
        assertEquals(0.25f, m.tick(playing(), 5_000).volume, 0.001f) // 5 s left: (0.5)^2
        var last = 0.25f
        repeat(4) {
            val v = m.tick(playing(), 1_000).volume
            assertTrue(v < last)
            last = v
        }
        assertEquals(0.01f, last, 0.001f) // 1 s left
    }

    @Test fun end_pausesRestoresVolumeAndTurnsOff() {
        m.start(SleepMode.Minutes(1), playing())
        m.tick(playing(), 55_000)
        val end = m.tick(playing(), 5_000)
        assertTrue(end.pause)
        assertEquals(1f, end.volume, 0f)
        assertFalse(m.isActive)
        assertEquals(SleepEffect(1f, pause = false), m.tick(playing(), 1_000))
    }

    @Test fun cancel_turnsOff() {
        m.start(SleepMode.Minutes(1), playing())
        m.tick(playing(), 55_000)
        m.cancel()
        assertFalse(m.isActive)
        assertEquals(1f, m.tick(playing(), 1_000).volume, 0f)
    }

    @Test fun shake_onlyInTheLastMinute_addsTheChosenDuration() {
        m.start(SleepMode.Minutes(5), playing())
        assertFalse(m.inShakeWindow(playing()))
        assertFalse(m.shake(playing()))
        m.tick(playing(), 4 * 60_000L + 55_000) // 5 s left, fading
        assertTrue(m.inShakeWindow(playing()))
        assertTrue(m.shake(playing()))
        assertEquals(305_000L, m.remainingMs(playing()))
        assertEquals(1f, m.tick(playing(), 0).volume, 0f)
        assertFalse(m.inShakeWindow(playing()))
    }

    @Test fun shake_whenOff_doesNothing() {
        assertFalse(m.shake(playing()))
        assertFalse(m.inShakeWindow(playing()))
    }

    private val ends = listOf(100.0, 200.0, 300.0)

    @Test fun chapter_remainingFollowsPositionAndSpeed() {
        m.start(SleepMode.EndOfChapter, playing(pos = 40.0, chapter = 0, ends = ends))
        assertEquals(60_000L, m.remainingMs(playing(pos = 40.0, chapter = 0, ends = ends)))
        assertEquals(30_000L, m.remainingMs(playing(pos = 40.0, chapter = 0, ends = ends, speed = 2f)))
    }

    @Test fun chapter_fadesAndPausesAtTheChapterEnd() {
        m.start(SleepMode.EndOfChapter, playing(pos = 40.0, chapter = 0, ends = ends))
        assertEquals(0.25f, m.tick(playing(pos = 95.0, chapter = 0, ends = ends), 250).volume, 0.001f)
        val end = m.tick(playing(pos = 99.9, chapter = 0, ends = ends), 250)
        assertTrue(end.pause)
        assertFalse(m.isActive)
    }

    @Test fun chapter_naturalCrossingBetweenTicks_ends() {
        m.start(SleepMode.EndOfChapter, playing(pos = 99.0, chapter = 0, ends = ends))
        m.tick(playing(pos = 99.5, chapter = 0, ends = ends), 250)
        val end = m.tick(playing(pos = 100.2, chapter = 1, ends = ends), 250)
        assertTrue(end.pause)
    }

    @Test fun chapter_manualJumpPastTheEnd_reanchorsToTheNewChapter() {
        m.start(SleepMode.EndOfChapter, playing(pos = 10.0, chapter = 0, ends = ends))
        val e = m.tick(playing(pos = 100.0, chapter = 1, ends = ends), 250) // "next chapter" from far away
        assertFalse(e.pause)
        assertTrue(m.isActive)
        assertEquals(100_000L, m.remainingMs(playing(pos = 100.0, chapter = 1, ends = ends)))
    }

    @Test fun chapter_shakeOutsideTheWindowOrInTheLastChapter_doesNothing() {
        m.start(SleepMode.EndOfChapter, playing(pos = 10.0, chapter = 0, ends = ends))
        assertFalse(m.shake(playing(pos = 10.0, chapter = 0, ends = ends))) // 90 s left
        m.start(SleepMode.EndOfChapter, playing(pos = 250.0, chapter = 2, ends = ends))
        assertFalse(m.shake(playing(pos = 250.0, chapter = 2, ends = ends)))
        assertEquals(50_000L, m.remainingMs(playing(pos = 250.0, chapter = 2, ends = ends)))
    }

    @Test fun chapter_shakeInWindow_extendsToTheNextChapterEnd() {
        m.start(SleepMode.EndOfChapter, playing(pos = 95.0, chapter = 0, ends = ends))
        assertTrue(m.shake(playing(pos = 95.0, chapter = 0, ends = ends)))
        assertEquals(105_000L, m.remainingMs(playing(pos = 95.0, chapter = 0, ends = ends)))
        // Playing on into chapter 1 is not a crossing of the (new) end.
        assertFalse(m.tick(playing(pos = 100.2, chapter = 1, ends = ends), 250).pause)
        m.tick(playing(pos = 290.0, chapter = 2, ends = ends), 250) // jumped: re-anchors to chapter 2
        assertFalse(m.shake(playing(pos = 290.0, chapter = 2, ends = ends))) // last chapter
    }

    @Test fun chapter_withoutChapters_endsAtTheEndOfTheTitle() {
        m.start(SleepMode.EndOfChapter, playing(pos = 3500.0, duration = 3600.0))
        assertEquals(100_000L, m.remainingMs(playing(pos = 3500.0, duration = 3600.0)))
        assertTrue(m.tick(playing(pos = 3599.9, duration = 3600.0), 250).pause)
    }

    @Test fun minutes_cancelAfterTenMinutesOfContinuousPause() {
        m.start(SleepMode.Minutes(30), playing())
        val paused = playing().copy(isPlaying = false)
        m.tick(paused, 9 * 60_000L)
        m.tick(playing(), 1_000) // playing again resets the pause clock
        m.tick(paused, 9 * 60_000L)
        assertTrue(m.isActive)
        val e = m.tick(paused, 60_000L)
        assertFalse(m.isActive)
        assertEquals(SleepEffect(1f, pause = false), e)
    }

    @Test fun chapter_rearm_anchorsToTheCurrentChapterOfTheNewTitle() {
        m.start(SleepMode.EndOfChapter, playing(pos = 250.0, chapter = 2, ends = ends))
        val other = playing(pos = 10.0, chapter = 0, ends = listOf(50.0, 500.0))
        m.rearm(other)
        assertEquals(40_000L, m.remainingMs(other))
        assertFalse(m.tick(other, 250).pause)
    }

    @Test fun chime_onceWhenThirtySecondsAreLeft() {
        m.start(SleepMode.Minutes(1), playing())
        assertFalse(m.tick(playing(), 29_000).playChime) // 31 s left
        assertTrue(m.tick(playing(), 2_000).playChime) // 29 s left: the hint
        assertFalse(m.tick(playing(), 1_000).playChime) // only once
        assertFalse(m.tick(playing(), 20_000).playChime) // into the fade
    }

    @Test fun chime_alsoWhenOneTickJumpsAcrossTheThreshold() {
        m.start(SleepMode.Minutes(1), playing())
        assertTrue(m.tick(playing(), 55_000).playChime)
    }

    @Test fun chime_againAfterAShakeExtendedTheTimer() {
        m.start(SleepMode.Minutes(1), playing())
        assertTrue(m.tick(playing(), 35_000).playChime) // 25 s left
        assertTrue(m.shake(playing())) // 85 s left
        assertFalse(m.tick(playing(), 50_000).playChime) // 35 s left
        assertTrue(m.tick(playing(), 10_000).playChime) // 25 s left again
    }

    @Test fun chime_notForATimerStartedWithLessThanThirtySecondsLeft() {
        val ends = listOf(100.0, 200.0)
        m.start(SleepMode.EndOfChapter, playing(pos = 80.0, chapter = 0, ends = ends))
        assertFalse(m.tick(playing(pos = 81.0, chapter = 0, ends = ends), 1_000).playChime)
        assertFalse(m.tick(playing(pos = 90.0, chapter = 0, ends = ends), 9_000).playChime)
    }

    @Test fun chime_chapterMode_whenTheChapterEndComesNear() {
        val ends = listOf(100.0, 200.0)
        m.start(SleepMode.EndOfChapter, playing(pos = 50.0, chapter = 0, ends = ends))
        assertFalse(m.tick(playing(pos = 69.0, chapter = 0, ends = ends), 19_000).playChime) // 31 s left
        assertTrue(m.tick(playing(pos = 71.0, chapter = 0, ends = ends), 2_000).playChime) // 29 s left
    }

    @Test fun chime_notWhilePaused() {
        val ends = listOf(100.0, 200.0)
        m.start(SleepMode.EndOfChapter, playing(pos = 50.0, chapter = 0, ends = ends))
        // Jumped close to the end while paused: no sound into the silence.
        assertFalse(m.tick(playing(pos = 90.0, chapter = 0, ends = ends).copy(isPlaying = false), 250).playChime)
    }
}
