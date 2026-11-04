package io.wolftown.lauschig.stats

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MidnightTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    @Test fun firesAtTheWallClocksMidnight_evenWhenTheClockJumps() = runTest {
        var offset = at("2026-11-04T22:00") // wall clock = test time + offset
        var done = false
        launch { awaitMidnight({ offset + testScheduler.currentTime }, { berlin }); done = true }

        advanceTimeBy(60 * 60_000L) // 23:00
        runCurrent()
        assertFalse(done)
        offset += 2 * 60 * 60_000L // the clock is set forward two hours (now 01:00 the next day), uptime did not move
        advanceTimeBy(MIDNIGHT_STEP_MS + 1)
        runCurrent()
        assertTrue(done) // noticed within one step, not after the hour the first delay would have waited
    }

    @Test fun firesOnceTheDayChanges_notBefore() = runTest {
        val start = at("2026-11-04T23:59")
        var done = false
        launch { awaitMidnight({ start + testScheduler.currentTime }, { berlin }); done = true }

        advanceTimeBy(59_000L)
        runCurrent()
        assertFalse(done)
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(done)
    }
}
