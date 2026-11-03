package io.wolftown.lauschig.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import io.wolftown.lauschig.connection.ConnectionState
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettledOfflineTest {
    @get:Rule val rule = createComposeRule()
    private val online = ConnectionState.Online("https://abs.example.org/".toHttpUrl(), ConnectionState.Via.PUBLIC)

    @Test fun briefOfflineAtStart_neverSettles() {
        var state: ConnectionState by mutableStateOf(ConnectionState.Offline)
        var settled = false
        rule.setContent { settled = rememberSettledOffline(state) }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS / 2)
        rule.runOnIdle { assertFalse(settled) }
        state = online
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS * 2)
        rule.runOnIdle { assertFalse(settled) }
    }

    @Test fun persistentOffline_settlesAfterDelay_andClearsImmediatelyWhenOnline() {
        var state: ConnectionState by mutableStateOf(ConnectionState.Offline)
        var settled = false
        rule.setContent { settled = rememberSettledOffline(state) }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS + 100)
        rule.runOnIdle { assertTrue(settled) }
        state = online
        rule.runOnIdle { }
        rule.waitForIdle()
        assertEquals(false, settled)
    }

    @Test fun firstCheckStillRunning_neverSettlesToOffline_showsConnecting() {
        var checking by mutableStateOf(true)
        var settled = true
        var banner: ConnectionBanner? = null
        rule.setContent {
            settled = rememberSettledOffline(ConnectionState.Offline, checking)
            banner = rememberConnectionBanner(ConnectionState.Offline, checking)
        }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS / 2)
        rule.runOnIdle { assertEquals(null, banner); assertFalse(settled) }
        // A slow first probe (unreachable local address, then the public one): "Verbinde …", no dimming.
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS)
        rule.runOnIdle { assertEquals(ConnectionBanner.CONNECTING, banner); assertFalse(settled) }
        // The probe answered "offline": now it is offline, at once.
        checking = false
        rule.waitForIdle()
        assertEquals(ConnectionBanner.OFFLINE, banner)
        assertTrue(settled)
    }

    @Test fun firstCheckAnswersOnline_noBannerAtAll() {
        var state: ConnectionState by mutableStateOf(ConnectionState.Offline)
        var checking by mutableStateOf(true)
        var banner: ConnectionBanner? = null
        rule.setContent { banner = rememberConnectionBanner(state, checking) }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS - 500)
        state = online
        checking = false
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS * 2)
        rule.runOnIdle { assertEquals(null, banner) }
    }
}
