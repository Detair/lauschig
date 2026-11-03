package io.wolftown.lauschig.playback

import android.content.Intent
import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ForegroundStartGuardTest {
    private var engaged = false
    /** Every settle, with its "stop" flag. */
    private val settles = mutableListOf<Boolean>()
    private val timeout = 7_000L

    private fun guard(scope: kotlinx.coroutines.CoroutineScope) = ForegroundStartGuard(scope, { engaged }, { stop -> settles += stop }, timeout)

    @Test fun failedResumption_settlesAndStopsAtOnce() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf(true), settles)
        advanceTimeBy(timeout * 2)
        runCurrent()
        assertEquals(listOf(true), settles)
    }

    @Test fun successfulResumption_thenPlaying_leavesItToMedia3() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        engaged = true
        g.onEngaged()
        advanceTimeBy(timeout * 2)
        runCurrent()
        assertEquals(emptyList<Boolean>(), settles)
    }

    @Test fun successfulResumption_butNothingPlays_settlesAtTheDeadline() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf(true), settles)
    }

    @Test fun deadlineWithTheResumptionStillPending_goesForeground_andStopsOnlyOnFailure() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(emptyList<Boolean>(), settles)
        advanceTimeBy(2)
        runCurrent()
        // In the foreground with the placeholder, but still waiting for the answer (a slow server, a details fetch).
        assertEquals(listOf(false), settles)
        assertTrue(g.armed)
        g.resumption(ok = false)
        assertEquals(listOf(false, true), settles)
        assertFalse(g.armed)
    }

    @Test fun deadlineWithTheResumptionStillPending_thenItPlays() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        g.resumption(ok = true)
        engaged = true
        g.onEngaged()
        assertEquals(listOf(false), settles)
        assertFalse(g.armed)
    }

    @Test fun repeatedPresses_keepTheFirstDeadline() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(5_000)
        g.arm()
        advanceTimeBy(timeout - 5_000 + 1)
        runCurrent()
        assertEquals(listOf(false), settles)
    }

    @Test fun timeoutPerApiLevel_keepsAMarginToAndroidsDeadline() {
        assertEquals(7_000L, ForegroundStartGuard.timeoutFor(28))
        assertEquals(7_000L, ForegroundStartGuard.timeoutFor(33))
        assertEquals(20_000L, ForegroundStartGuard.timeoutFor(34))
        assertEquals(20_000L, ForegroundStartGuard.timeoutFor(35))
    }

    @Test fun resumptionWithoutAPlayKey_isIgnored() = runTest {
        // SystemUI's resumption card or a car: no media-button start, nothing to settle.
        val g = guard(backgroundScope)
        g.resumption(ok = false)
        assertEquals(emptyList<Boolean>(), settles)
        assertFalse(g.armed)
    }

    @Test fun engagedBeforeTheTimeout_disarmsForGood() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        engaged = true
        g.onEngaged()
        // The user pauses within the timeout: still nothing to settle (Media3 owns the notification now).
        engaged = false
        advanceTimeBy(timeout * 2)
        runCurrent()
        assertEquals(emptyList<Boolean>(), settles)
        assertFalse(g.armed)
        g.resumption(ok = false)
        assertEquals(emptyList<Boolean>(), settles)
    }

    @Test fun playKeys() {
        fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
        assertTrue(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertTrue(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertTrue(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_HEADSETHOOK)))
        assertFalse(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertFalse(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_MEDIA_REWIND)))
        assertFalse(ForegroundStartGuard.isPlayKey(key(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP)))
        assertFalse(ForegroundStartGuard.isPlayKey(Intent("other")))
    }

    @Test fun onlyAnEmptyPlayer_arms() {
        fun key(code: Int) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))
        assertTrue(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY), mediaItemCount = 0))
        // A paused, loaded title: PLAY just plays it, Media3 goes foreground itself.
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY), mediaItemCount = 3))
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PAUSE), mediaItemCount = 0))
    }

    @Test fun alreadyInTheForeground_isNeverTakenOver() {
        // API 29+: Media3 (or anyone) made the service foreground; settling would swap its notification id.
        assertFalse(ForegroundStartGuard.maySettle(sdk = 35, foregroundServiceType = 2, ownPlaceholder = false))
        assertTrue(ForegroundStartGuard.maySettle(sdk = 35, foregroundServiceType = 0, ownPlaceholder = false))
        // Our own placeholder may of course be stopped again.
        assertTrue(ForegroundStartGuard.maySettle(sdk = 35, foregroundServiceType = 2, ownPlaceholder = true))
        // API 28 cannot tell; the arming rules above keep it away from a playing service.
        assertTrue(ForegroundStartGuard.maySettle(sdk = 28, foregroundServiceType = 0, ownPlaceholder = false))
    }
}
