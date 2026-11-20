package io.wolftown.lauschig.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The TV's code page tells the person to open Settings, then „Set up TV“: the row must carry that very name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp")
class TvSetupEntryTest {
    @get:Rule val rule = createComposeRule()
    private var opened = 0

    private fun show(signedIn: Boolean) = rule.setContent {
        LauschigTheme {
            SettingsScreen(
                SettingsUiState(signedIn = signedIn), onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {},
                onOpenTvSetup = { opened++ },
            )
        }
    }

    @Test fun signedIn_theRowIsThere_andOpensThePage() {
        show(signedIn = true)
        rule.onNodeWithTag("openTvSetup").performScrollTo()
        rule.onNodeWithText("Set up TV").assertExists()
        rule.onNodeWithText("Sign in Lauschig on a TV with this phone").assertExists()
        rule.onNodeWithTag("openTvSetup").performClick()
        assertEquals(1, opened)
    }

    @Test fun withoutAnAccount_thereIsNoRow() {
        show(signedIn = false)
        rule.onNodeWithTag("openTvSetup").assertDoesNotExist()
    }
}
