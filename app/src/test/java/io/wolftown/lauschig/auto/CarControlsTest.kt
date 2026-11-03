package io.wolftown.lauschig.auto

import io.wolftown.lauschig.playback.SleepMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarControlsTest {
    @Test fun speed_cyclesThroughTheSteps_andSnapsUpFromOddValues() {
        assertEquals(1.2f, CarControls.nextSpeed(1.0f))
        assertEquals(1.5f, CarControls.nextSpeed(1.2f))
        assertEquals(1.8f, CarControls.nextSpeed(1.5f))
        assertEquals(2.0f, CarControls.nextSpeed(1.8f))
        assertEquals(1.0f, CarControls.nextSpeed(2.0f))
        assertEquals(1.5f, CarControls.nextSpeed(1.3f))
        assertEquals(1.0f, CarControls.nextSpeed(0.8f))
        assertEquals(1.0f, CarControls.nextSpeed(3.0f))
        assertEquals(1.2f, CarControls.nextSpeed(1.0001f)) // float noise is the same step
    }

    @Test fun sleep_cycle() {
        assertEquals(SleepMode.Minutes(15), CarControls.nextSleep(null))
        assertEquals(SleepMode.Minutes(30), CarControls.nextSleep(SleepMode.Minutes(15)))
        assertEquals(SleepMode.Minutes(60), CarControls.nextSleep(SleepMode.Minutes(30)))
        assertEquals(SleepMode.EndOfChapter, CarControls.nextSleep(SleepMode.Minutes(60)))
        assertNull(CarControls.nextSleep(SleepMode.EndOfChapter))
        assertEquals(SleepMode.EndOfChapter, CarControls.nextSleep(SleepMode.Minutes(45))) // set in the app
    }

    private val durations = listOf(60_000L, 20_000L, 100_000L)
    private fun d(i: Int) = durations.getOrNull(i)

    @Test fun stepForward_crossesFiles_andStopsAtTheEnd() {
        assertEquals(0 to 40_000L, CarControls.stepForward(0, 10_000, 30_000, durations.size, ::d))
        assertEquals(1 to 10_000L, CarControls.stepForward(0, 40_000, 30_000, durations.size, ::d))
        assertEquals(2 to 5_000L, CarControls.stepForward(0, 55_000, 30_000, durations.size, ::d))
        assertEquals(2 to 100_000L, CarControls.stepForward(2, 90_000, 30_000, durations.size, ::d))
        // Unknown length of the current file: the player clamps.
        assertEquals(0 to 40_000L, CarControls.stepForward(0, 10_000, 30_000, 1) { null })
    }

    @Test fun stepBack_isTheResumeRewindRule() {
        assertEquals(0 to 50_000L, CarControls.stepBack(1, 20_000 - 10_000, 20_000, ::d))
        assertEquals(0 to 0L, CarControls.stepBack(0, 10_000, 30_000, ::d))
    }

    private val starts = listOf(0.0, 100.0, 250.0)

    @Test fun chapterTargets() {
        assertEquals(100.0, CarControls.chapterBack(starts, 150.0)!!, 0.0) // well into chapter 2: its start
        assertEquals(0.0, CarControls.chapterBack(starts, 102.0)!!, 0.0) // just started: the previous one
        assertEquals(0.0, CarControls.chapterBack(starts, 1.0)!!, 0.0)
        assertEquals(250.0, CarControls.chapterNext(starts, 150.0)!!, 0.0)
        assertNull(CarControls.chapterNext(starts, 260.0))
        assertNull(CarControls.chapterBack(emptyList(), 10.0))
        assertNull(CarControls.chapterNext(emptyList(), 10.0))
    }
}
