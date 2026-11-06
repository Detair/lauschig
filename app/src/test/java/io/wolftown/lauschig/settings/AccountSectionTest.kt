package io.wolftown.lauschig.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
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
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class AccountSectionTest {
    @get:Rule val rule = createComposeRule()
    private val account = AccountInfo("fabian", "abs.example.org", isAdmin = true)
    private val calls = mutableListOf<Any>()

    private fun show(step: SignOutStep?, info: AccountInfo? = account) = rule.setContent {
        LauschigTheme {
            Column {
                AccountSectionContent(info, step, { calls += "signOut" }, { calls += "keep:$it" }, { calls += "confirm" }, { calls += "cancel" }, onOpenProfiles = { calls += "profiles" })
            }
        }
    }

    @Test fun theProfileRow_nameServerAdmin_opensTheSheet_andSignOut() {
        show(null)
        rule.onNodeWithText("Account").assertExists()
        rule.onNodeWithText("Profile: fabian · abs.example.org").performClick()
        rule.onNodeWithText("Admin · Switch or add").assertExists()
        rule.onNodeWithTag("signOut").performClick()
        assertEquals(listOf<Any>("profiles", "signOut"), calls)
    }

    @Test fun noAdmin_justTheHint() {
        show(null, account.copy(isAdmin = false))
        rule.onNodeWithText("Switch or add").assertExists()
    }

    @Test fun checking_showsTheUpload_andCanOnlyBeCancelled() {
        show(SignOutStep.Checking)
        rule.onNodeWithText("Uploading your progress …").assertExists()
        rule.onNodeWithTag("confirmSignOut").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf<Any>("cancel"), calls)
    }

    @Test fun question_keepCheckedByDefault_withTheSize_andTheKeptWarning() {
        show(SignOutStep.Confirm(unsynced = 3, downloadBytes = 1_500_000_000))
        rule.onNodeWithText("Sign out?").assertExists()
        rule.onNodeWithTag("keepDownloads").assertIsOn()
        rule.onNodeWithText("Keep downloads on this device (1.5\u00A0GB)").assertExists()
        rule.onNodeWithText("3 changes are not on the server yet. They are uploaded when you sign in with this account again.").assertExists()
        rule.onNodeWithTag("keepDownloads").performClick()
        rule.onNodeWithTag("confirmSignOut").performClick()
        assertEquals(listOf<Any>("keep:false", "confirm"), calls)
    }

    @Test fun question_notKeeping_warnsThatChangesAreLost() {
        show(SignOutStep.Confirm(unsynced = 1, downloadBytes = 10, keepDownloads = false))
        rule.onNodeWithTag("keepDownloads").assertIsOff()
        rule.onNodeWithText("1 change is not on the server yet and will be lost when you sign out.").assertExists()
    }

    @Test fun question_changesButNoDownloads_offersToKeepTheData() {
        show(SignOutStep.Confirm(unsynced = 3, downloadBytes = 0))
        rule.onNodeWithTag("keepDownloads").assertIsOn()
        rule.onNodeWithText("Keep data on this device").assertExists()
        rule.onNodeWithText("3 changes are not on the server yet. They are uploaded when you sign in with this account again.").assertExists()
    }

    @Test fun question_unknownCount_withDownloads_namesThemWithTheirSize() {
        show(SignOutStep.Confirm(unsynced = null, downloadBytes = 1_500_000_000))
        rule.onNodeWithText("Keep downloads on this device (1.5\u00A0GB)").assertExists()
        rule.onNodeWithText("Whether changes are still missing on the server could not be checked.").assertExists()
    }

    @Test fun question_unknownCount_saysSo_andOffersToKeep() {
        show(SignOutStep.Confirm(unsynced = null, downloadBytes = 0))
        rule.onNodeWithText("Whether changes are still missing on the server could not be checked.").assertExists()
        rule.onNodeWithTag("keepDownloads").assertIsOn()
    }

    @Test fun question_withoutDownloads_hasNoCheckbox_andNothingPending_noWarning() {
        show(SignOutStep.Confirm(unsynced = 0, downloadBytes = 0))
        rule.onNodeWithTag("keepDownloads").assertDoesNotExist()
        rule.onNodeWithTag("unsyncedWarning").assertDoesNotExist()
        rule.onNodeWithText("This device will be signed out of abs.example.org.").assertExists()
    }

    @Test fun signingOut_hasNoWayOut() {
        show(SignOutStep.SigningOut)
        rule.onNodeWithText("Signing out …").assertExists()
        rule.onNodeWithTag("confirmSignOut").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun settings_theAccountIsTheFirstSection() {
        rule.setContent {
            LauschigTheme {
                SettingsScreen(
                    SettingsUiState(), onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {},
                    accountSection = { AccountSectionContent(account, null, {}, {}, {}, {}) },
                )
            }
        }
        val tree = rule.onRoot().printToString()
        assertTrue(tree.indexOf("Account") in 0 until tree.indexOf("Playback"))
        rule.onNodeWithTag("settingsScreen").performScrollToNode(hasTestTag("signOut"))
    }

    @Test fun aCardThatIsOut_isSaid_deletingWaitsForIt() {
        show(SignOutStep.Confirm(unsynced = 0, downloadBytes = 0, keepDownloads = false, onMissingCard = true))
        // Downloads only on the card: still something to keep.
        rule.onNodeWithTag("keepDownloads").assertExists()
        rule.onNodeWithTag("cardMissingNote").assertTextEquals("Downloads on the SD card, which is out right now, are deleted as soon as it is back.")
    }

    @Test fun aCardThatIsOut_keeping_saysTheyStay() {
        show(SignOutStep.Confirm(unsynced = 0, downloadBytes = 10_000_000, keepDownloads = true, onMissingCard = true))
        rule.onNodeWithTag("cardMissingNote").assertTextEquals("Some downloads are on the SD card, which is out right now; they stay as well.")
    }

    /** Task 2's re-review: as the removal dialog. A short window, large text, and its last line can be the warning. */
    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "w640dp-h300dp")
    fun theDialog_scrollsToItsLastLine() {
        show(SignOutStep.Confirm(unsynced = 3, downloadBytes = 1_500_000_000, onMissingCard = true, keepDownloads = false))
        rule.onNodeWithTag("signOutText").assert(hasScrollAction())
    }
}
