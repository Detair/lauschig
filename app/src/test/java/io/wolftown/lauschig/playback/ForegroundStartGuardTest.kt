package io.wolftown.lauschig.playback

import android.content.Intent
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
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
    /** Stands in for PlaybackService: records what the guard asks it to do. */
    private class FakeHost : ForegroundStartGuard.Host {
        var engaged = false
        var hasItems = false
        /** Media3's media notification is posted (a loaded, paused title). */
        var mediaNotification = false
        val calls = mutableListOf<String>()
        override fun engaged() = engaged
        override fun hasItems() = hasItems
        override fun adoptMediaNotification(): Boolean {
            if (!mediaNotification) return false
            calls += "adopt"
            return true
        }
        override fun showPlaceholder() { calls += "placeholder" }
        override fun leavePlaceholder() { calls += "leave" }
    }

    private val host = FakeHost()
    private val timeout = 7_000L
    private val cap = 60_000L

    /** Up to API 34: nothing is asked of Android before the deadline. */
    private fun guard(scope: CoroutineScope) = ForegroundStartGuard(scope, host, timeout, cap, foregroundFirst = false)

    /** API 35+: audio focus needs the foreground, so the guard goes there before Media3 handles the key. */
    private fun firstGuard(scope: CoroutineScope) = ForegroundStartGuard(scope, host, timeout, cap, foregroundFirst = true)

    @Test fun foregroundFirst_emptyPlayer_placeholderAtTheKey_thenMedia3TakesOver() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("placeholder"), host.calls) // before the key reaches Media3: focus is asked for right after
        assertTrue(g.armed)
        g.resumption(ok = true)
        host.engaged = true
        g.onEngaged()
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls) // Media3's startForeground replaces it, the guard does not touch it
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_loadedTitle_adoptsMedia3sNotificationAtTheKey() = runTest {
        // A paused title past Media3's foreground timeout: no placeholder, nothing is swapped, the guard is done at once.
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("adopt"), host.calls)
        assertFalse(g.armed)
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("adopt"), host.calls)
    }

    @Test fun foregroundFirst_failingResumption_leavesAtOnce_withOnePlaceholder() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun foregroundFirst_nothingPlays_leavesAtTheDeadline() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_slowResumption_keepsThePlaceholderUpToTheCap() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + cap - 10)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        assertTrue(g.armed)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_secondPress_doesNotGoForegroundAgain() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.arm()
        assertEquals(listOf("placeholder"), host.calls)
    }

    @Test fun foregroundFirst_fromApi35() {
        assertFalse(ForegroundStartGuard.foregroundFirstFor(34))
        assertTrue(ForegroundStartGuard.foregroundFirstFor(35))
        assertTrue(ForegroundStartGuard.foregroundFirstFor(37))
    }

    @Test fun emptyPlayer_failingResumption_placeholderAndStop_atOnce() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun secondFailingCycle_goesForegroundAgain() = runTest {
        // A bound client (car, SystemUI browser) keeps the service alive after the first settle; on API 29–33 Android
        // still reports the old foreground type then. The guard never asks: every cycle calls startForeground again.
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave", "placeholder", "leave"), host.calls)
    }

    @Test fun loadedTitle_playKeyThatNeverPlays_adoptsMedia3sNotification() = runTest {
        // Double tap of the headset key on a paused title: Media3 skips +30 s, nothing plays. The deadline is met with
        // Media3's own notification id – nothing is swapped, the service is not stopped, the title keeps its controls.
        host.hasItems = true
        host.mediaNotification = true
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("adopt"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun loadedTitle_withoutMedia3sNotification_placeholderThenLeave() = runTest {
        host.hasItems = true
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun emptyPlayer_doesNotAdoptAStaleNotification() = runTest {
        host.mediaNotification = true
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun pendingResumption_placeholderAtTheDeadline_capStopsIt() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        assertTrue(g.armed)
        // The cap counts from the deadline (now + cap - 1 ms).
        advanceTimeBy(cap - 10)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
        // And the next play key arms again.
        g.arm()
        assertTrue(g.armed)
    }

    @Test fun pendingResumption_thatFailsLater_stopsThen() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave"), host.calls)
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun pendingResumption_thatPlaysLater_handsOverToMedia3() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout + 1)
        runCurrent()
        g.resumption(ok = true)
        host.engaged = true
        g.onEngaged()
        advanceTimeBy(cap * 2)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun successfulResumption_butNothingPlays_leavesAtTheDeadline() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun engaged_neverSettles_alsoAfterAQuickPause() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        val g = guard(backgroundScope)
        g.arm()
        host.engaged = true
        g.onEngaged()
        host.engaged = false // paused within the timeout
        advanceTimeBy(cap * 2)
        runCurrent()
        g.resumption(ok = false)
        assertEquals(emptyList<String>(), host.calls)
        assertFalse(g.armed)
    }

    @Test fun repeatedPresses_keepTheFirstDeadline() = runTest {
        host.hasItems = true
        val g = guard(backgroundScope)
        g.arm()
        advanceTimeBy(5_000)
        g.arm()
        advanceTimeBy(timeout - 5_000 + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun resumptionWithoutAPlayKey_isIgnored() = runTest {
        // SystemUI's resumption card or a car: no media-button start, nothing to settle.
        val g = guard(backgroundScope)
        g.resumption(ok = false)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test fun timeoutPerApiLevel_keepsAMarginToAndroidsDeadline() {
        assertEquals(7_000L, ForegroundStartGuard.timeoutFor(28))
        assertEquals(7_000L, ForegroundStartGuard.timeoutFor(33))
        assertEquals(20_000L, ForegroundStartGuard.timeoutFor(34))
    }

    @Test fun arming_everyPlayKey_unlessSomethingPlays() {
        fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
        assertTrue(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY), engaged = false))
        assertTrue(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_HEADSETHOOK), engaged = false))
        assertTrue(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), engaged = false))
        // Playing: Media3 is in the foreground, Android sets no new deadline for a start.
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY), engaged = true))
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PAUSE), engaged = false))
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_REWIND), engaged = false))
        assertFalse(ForegroundStartGuard.shouldArm(key(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP), engaged = false))
        assertFalse(ForegroundStartGuard.shouldArm(Intent("other"), engaged = false))
    }
}

class MediaNotificationIdTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun theGuardAdoptsTheIdMedia3Posts() {
        assertEquals(androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, ForegroundStartGuard.MEDIA_NOTIFICATION_ID)
    }
}
