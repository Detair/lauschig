package io.wolftown.lauschig.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
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

    private fun show(state: SettingsUiState, onAutoDelete: (AutoDeleteMode) -> Unit = {}, onConfirm: () -> Unit = {}, onCancel: () -> Unit = {}) = rule.setContent {
        LauschigTheme {
            SettingsScreen(
                state, onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {}, onAutoDelete = onAutoDelete,
                onAutoDeleteConfirm = onConfirm, onAutoDeleteCancel = onCancel,
            )
        }
    }

    @Test fun turningItOn_withHeardDownloads_asksFirst_withCountAndSize() {
        var confirmed = 0
        var cancelled = 0
        show(SettingsUiState(autoDeleteConfirm = AutoDeleteConfirm(AutoDeleteMode.IMMEDIATELY, 3, 1_200_000_000, "aaaaaaaaaaaaaaaa")), onConfirm = { confirmed++ }, onCancel = { cancelled++ })
        rule.onNodeWithTag("autoDeleteConfirmText").assertTextEquals("3 heard downloads (1.2\u00A0GB) will be deleted right away.")
        rule.onNodeWithText("To keep a title, mark it “Keep” under “Clean up” first.").assertExists()
        rule.onNodeWithTag("autoDeleteCancel").performClick()
        rule.onNodeWithTag("autoDeleteConfirm").performClick()
        assertEquals(1, cancelled)
        assertEquals(1, confirmed)
    }

    @Test fun theQuestion_forADelay_saysWhen() {
        show(SettingsUiState(autoDeleteConfirm = AutoDeleteConfirm(AutoDeleteMode.AFTER_7_DAYS, 1, 50_000_000, "aaaaaaaaaaaaaaaa")))
        rule.onNodeWithTag("autoDeleteConfirmText").assertTextEquals("1 heard download (50\u00A0MB) will be deleted, 7 days after it was heard.")
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
