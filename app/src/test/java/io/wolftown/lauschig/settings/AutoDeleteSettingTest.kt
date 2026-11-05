package io.wolftown.lauschig.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp")
class AutoDeleteSettingTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: SettingsUiState, onAutoDelete: (AutoDeleteMode) -> Unit = {}) = rule.setContent {
        LauschigTheme {
            SettingsScreen(state, onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {}, onAutoDelete = onAutoDelete)
        }
    }

    @Test fun theFourChoices_neverIsTheDefault() {
        val picked = mutableListOf<AutoDeleteMode>()
        show(SettingsUiState(), picked::add)
        rule.onNodeWithTag("autoDelete").performScrollTo()
        rule.onNodeWithText("Delete heard downloads automatically").assertExists()
        rule.onNodeWithText("Never").assertExists()
        rule.onNodeWithTag("autoDelete").performClick()
        rule.onNodeWithText("Right away").assertExists()
        rule.onNodeWithText("After 3 days").assertExists()
        rule.onNodeWithText("After 7 days").performClick()
        assertEquals(listOf(AutoDeleteMode.AFTER_7_DAYS), picked)
    }

    @Test fun theReminder_restsWhileHeardDownloadsAreDeletedAutomatically() {
        show(SettingsUiState(cleanupReminder = true, autoDelete = AutoDeleteMode.AFTER_3_DAYS))
        rule.onNodeWithTag("cleanupReminder").performScrollTo().assertIsNotEnabled().assertIsNotSelected()
        rule.onNodeWithText("Not needed while heard downloads are deleted automatically.").assertExists()
    }

    @Test fun withoutAutomaticDeletion_theReminderIsAsBefore() {
        show(SettingsUiState(cleanupReminder = true))
        rule.onNodeWithTag("cleanupReminder").performScrollTo().assertIsEnabled().assertIsSelected()
    }
}
