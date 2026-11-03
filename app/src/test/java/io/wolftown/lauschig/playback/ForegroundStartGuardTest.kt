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
    private var settled = 0

    @Test fun failedResumption_settlesAtOnce() = runTest {
        val g = ForegroundStartGuard(backgroundScope, { engaged }, { settled++ })
        g.arm()
        g.resumption(ok = false)
        assertEquals(1, settled)
        advanceTimeBy(ForegroundStartGuard.TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(1, settled)
    }

    @Test fun successfulResumption_leavesItToMedia3() = runTest {
        val g = ForegroundStartGuard(backgroundScope, { engaged }, { settled++ })
        g.arm()
        g.resumption(ok = true)
        advanceTimeBy(ForegroundStartGuard.TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(0, settled)
    }

    @Test fun noAnswer_settlesAfterTheTimeout_unlessSomethingPlays() = runTest {
        val g = ForegroundStartGuard(backgroundScope, { engaged }, { settled++ })
        g.arm()
        advanceTimeBy(ForegroundStartGuard.TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(0, settled)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, settled)

        engaged = true
        g.arm()
        advanceTimeBy(ForegroundStartGuard.TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(1, settled)
    }

    @Test fun resumptionWithoutAPlayKey_isIgnored() = runTest {
        // SystemUI's resumption card or a car: no media-button start, nothing to settle.
        val g = ForegroundStartGuard(backgroundScope, { engaged }, { settled++ })
        g.resumption(ok = false)
        assertEquals(0, settled)
        assertFalse(g.armed)
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
}
