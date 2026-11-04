package io.wolftown.lauschig.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStatsEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun statistics_followTheAccount_beforePlayback_andOpen() {
        var opened = 0
        rule.setContent {
            LauschigTheme {
                SettingsScreen(SettingsUiState(), onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {}, onOpenStats = { opened++ })
            }
        }
        val tree = rule.onRoot().printToString()
        assertTrue(tree.indexOf("Listening time, streaks and finished books") in 0 until tree.indexOf("Playback"))
        rule.onNodeWithTag("openStats").performClick()
        assertEquals(1, opened)
    }
}
