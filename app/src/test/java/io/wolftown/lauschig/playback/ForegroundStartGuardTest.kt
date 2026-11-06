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
        /** The notification is posted, but Android refuses `startForeground` with it. */
        var adoptionRefused = false
        val calls = mutableListOf<String>()
        override fun engaged() = engaged
        override fun hasItems() = hasItems
        override fun adoptMediaNotification(): Boolean {
            if (adoptionRefused) { calls += "adoptRefused"; return false }
            if (!mediaNotification) return false
            calls += "adopt"
            return true
        }
        override fun showPlaceholder() { calls += "placeholder" }
        override fun leavePlaceholder() { calls += "leave" }
        override fun cancelPlaceholder() { calls += "cancel" }
        override fun updateNotification() { calls += "update" }
    }

    private fun playKey(code: Int = KeyEvent.KEYCODE_MEDIA_PLAY) =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code))

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
        assertEquals(listOf("placeholder"), host.calls) // Media3's startForeground replaces it, nothing happens right now
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_keyThenForward_inThatOrder() = runTest {
        val g = firstGuard(backgroundScope)
        val result = g.onStart(playKey(), engaged = false) { host.calls += "forward"; 42 }
        // Media3 asks for audio focus when it handles the key: the foreground has to be there before it does.
        assertEquals(listOf("placeholder", "forward"), host.calls)
        assertEquals(42, result)
    }

    @Test fun foregroundFirst_loadedTitle_keyThenForward_inThatOrder() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.onStart(playKey(), engaged = false) { host.calls += "forward"; 0 }
        assertEquals(listOf("adopt", "forward"), host.calls)
    }

    @Test fun onStart_withoutAPlayKey_onlyForwards() = runTest {
        val g = firstGuard(backgroundScope)
        g.onStart(null, engaged = false) { host.calls += "forward"; 0 }
        g.onStart(playKey(KeyEvent.KEYCODE_MEDIA_PAUSE), engaged = false) { host.calls += "forward"; 0 }
        g.onStart(playKey(), engaged = true) { host.calls += "forward"; 0 }
        assertEquals(listOf("forward", "forward", "forward"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun beforeApi35_keyThenForward_doesNotGoForegroundFirst() = runTest {
        val g = guard(backgroundScope)
        g.onStart(playKey(), engaged = false) { host.calls += "forward"; 0 }
        assertEquals(listOf("forward"), host.calls)
        assertTrue(g.armed)
    }

    @Test fun foregroundFirst_foregroundLost_secondPress_goesForegroundAgain() = runTest {
        // Something took the foreground away after the first press; the second start set a new deadline at Android, and a
        // service that stops with that deadline open crashes the app.
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("placeholder"), host.calls)
        g.arm()
        assertEquals(listOf("placeholder", "placeholder"), host.calls)
        assertTrue(g.armed)
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "placeholder", "leave"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_secondPress_afterTheTitleLoaded_adoptsItsNotification() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        host.hasItems = true
        host.mediaNotification = true
        g.arm()
        assertEquals(listOf("placeholder", "adopt"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_loadedTitle_withoutPostedNotification_placeholderThenLeaveAtTheDeadline() = runTest {
        host.hasItems = true
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("placeholder"), host.calls)
        assertTrue(g.armed)
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave"), host.calls)
        assertFalse(g.armed)
    }

    @Test fun foregroundFirst_refusedAdoption_fallsBackToThePlaceholder() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        host.adoptionRefused = true
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("adoptRefused", "placeholder"), host.calls)
        assertTrue(g.armed)
    }

    @Test fun foregroundFirst_holdsMedia3sUpdates_whileArmed() = runTest {
        val g = firstGuard(backgroundScope)
        assertFalse(g.holdsNotificationUpdate()) // not armed: Media3 does as it likes
        g.arm()
        assertTrue(g.holdsNotificationUpdate())
        g.resumption(ok = false)
        assertFalse(g.holdsNotificationUpdate())
    }

    @Test fun beforeApi35_neverHoldsMedia3sUpdates() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        assertFalse(g.holdsNotificationUpdate())
    }

    @Test fun foregroundFirst_heldUpdate_runsOnceTheArmingEndsWithoutPlaying() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        assertTrue(g.holdsNotificationUpdate())
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave", "update"), host.calls)
    }

    @Test fun foregroundFirst_heldUpdate_atTheDeadline_runsAfterLeaving() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.resumption(ok = true)
        assertTrue(g.holdsNotificationUpdate())
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave", "update"), host.calls)
    }

    @Test fun foregroundFirst_heldUpdate_atTheCap_runsAfterLeaving() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        assertTrue(g.holdsNotificationUpdate())
        advanceTimeBy(timeout + cap + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "leave", "update"), host.calls)
    }

    @Test fun foregroundFirst_noHeldUpdate_noExtraUpdate() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        g.resumption(ok = false)
        assertEquals(listOf("placeholder", "leave"), host.calls)
    }

    @Test fun foregroundFirst_engaged_doesNotRunTheHeldUpdateRightAway() = runTest {
        // Media3's controller may not know the new title yet: an update now would end the foreground just after play().
        // Its own update follows with the player's change.
        val g = firstGuard(backgroundScope)
        g.arm()
        assertTrue(g.holdsNotificationUpdate())
        host.engaged = true
        g.onEngaged()
        assertEquals(listOf("placeholder"), host.calls)
    }

    @Test fun foregroundFirst_engaged_cancelsADetachedPlaceholderAtTheDeadline() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        host.engaged = true
        g.onEngaged()
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(listOf("placeholder"), host.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("placeholder", "cancel"), host.calls)
    }

    @Test fun foregroundFirst_engagedThenPaused_updatesAndCancelsAtTheDeadline() = runTest {
        // Play and pause within milliseconds: Media3's controller may have seen no change, so nothing ends the placeholder.
        val g = firstGuard(backgroundScope)
        g.arm()
        host.engaged = true
        g.onEngaged()
        host.engaged = false
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("placeholder", "update"), host.calls)
        // Media3 builds its notification asynchronously and only then detaches the placeholder.
        advanceTimeBy(ForegroundStartGuard.UPDATE_SETTLE_MS)
        runCurrent()
        assertEquals(listOf("placeholder", "update", "cancel"), host.calls)
    }

    @Test fun foregroundFirst_adopted_notEngagedAtTheDeadline_triggersAnUpdate() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.arm()
        advanceTimeBy(timeout - 1)
        runCurrent()
        assertEquals(listOf("adopt"), host.calls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("adopt", "update"), host.calls)
    }

    @Test fun foregroundFirst_adopted_playingAtTheDeadline_leavesItToMedia3() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.arm()
        host.engaged = true
        g.onEngaged()
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("adopt"), host.calls)
    }

    @Test fun release_endsWhatIsStillToCome() = runTest {
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.arm()
        g.release()
        advanceTimeBy(timeout + 1)
        runCurrent()
        assertEquals(listOf("adopt"), host.calls)
    }

    @Test fun release_doesNotRunAHeldUpdate() = runTest {
        val g = firstGuard(backgroundScope)
        g.arm()
        assertTrue(g.holdsNotificationUpdate())
        g.release()
        assertEquals(listOf("placeholder"), host.calls)
    }

    @Test fun foregroundFirst_loadedTitle_adoptsMedia3sNotificationAtTheKey() = runTest {
        // A paused title past Media3's foreground timeout: no placeholder, nothing is swapped, the guard is done at once.
        host.hasItems = true
        host.mediaNotification = true
        val g = firstGuard(backgroundScope)
        g.arm()
        assertEquals(listOf("adopt"), host.calls)
        assertFalse(g.armed)
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
        // A key on a paused title that Media3 does not turn into playback. The deadline is met with Media3's own
        // notification id – nothing is swapped, the service is not stopped, the title keeps its controls.
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

    @Test fun beforeApi35_secondPress_doesNothingYet() = runTest {
        val g = guard(backgroundScope)
        g.arm()
        g.arm()
        assertEquals(emptyList<String>(), host.calls)
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
