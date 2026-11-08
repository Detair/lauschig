package io.wolftown.lauschig.ui.setup

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.accessibilityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class SetupScreenTest {
    @get:Rule val rule = createComposeRule()
    private var backs = 0

    private fun show(state: SetupUiState) = rule.setContent {
        LauschigTheme {
            SetupScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onBack = { backs++ })
        }
    }

    @Test fun theFirstForm_asAlways_withoutAWayBack() {
        show(SetupUiState())
        rule.onNodeWithText("Connect to Audiobookshelf").assertExists()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun addingAProfile_saysSo_withAWayBack() {
        show(SetupUiState(addProfile = true))
        rule.onNodeWithText("Add profile").assertExists()
        rule.onNodeWithText("Connect to Audiobookshelf").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun addingWhileSomeoneIsActive_saysThatOneStays() {
        show(SetupUiState(addProfile = true, nobodyActive = false))
        rule.onNodeWithText("The current one stays signed in", substring = true).assertExists()
    }

    /** Task 2's re-review: the form from the picker must not flash that someone stays signed in. */
    @Test fun addingBeforeItIsKnownWhoIsActive_saysNeither() {
        show(SetupUiState(addProfile = true))
        rule.onNodeWithText("The current one", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Sign in with another account", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Add profile").assertExists()
    }

    /** From „Wer hört zu?“: nobody is active. */
    @Test fun addingWithNobodyActive_saysNothingAboutACurrentAccount() {
        show(SetupUiState(addProfile = true, nobodyActive = true))
        rule.onNodeWithText("The current one", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Sign in with another account", substring = true).assertExists()
    }

    /** A live region speaks when a node it knows changes: the first wrong password must be heard too. */
    @Test fun anErrorIsAnnounced_onANodeThatWasThereBefore() {
        var state by mutableStateOf(SetupUiState(addProfile = true))
        rule.setContent { LauschigTheme { SetupScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        val line = rule.onNodeWithTag("setupError")
        val before = line.fetchSemanticsNode().id
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, line.accessibilityInfo()!!.liveRegion)
        assertFalse(SemanticsProperties.ContentDescription in line.fetchSemanticsNode().config)

        state = state.copy(error = SetupError.WRONG_LOGIN)
        rule.waitForIdle()
        assertEquals(before, line.fetchSemanticsNode().id)
        assertEquals(listOf("Username or password is wrong."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])

        // Another error on the same node, and gone again when the fields change.
        state = state.copy(error = SetupError.UNREACHABLE)
        rule.waitForIdle()
        assertEquals(before, line.fetchSemanticsNode().id)
        state = state.copy(error = null)
        rule.waitForIdle()
        assertEquals(before, line.fetchSemanticsNode().id)
        assertFalse(SemanticsProperties.ContentDescription in line.fetchSemanticsNode().config)
    }

    @Test fun withoutAnError_theLineTakesNoRoom() {
        show(SetupUiState(addProfile = true))
        val height = rule.onNodeWithTag("setupError").fetchSemanticsNode().boundsInRoot.height
        assertTrue("$height", height in 0.5f..2f)
    }

    @Test fun anErrorCanBeSeen() {
        show(SetupUiState(error = SetupError.WRONG_LOGIN))
        val line = rule.onNodeWithTag("setupError").fetchSemanticsNode().boundsInRoot
        assertTrue(line.height > 20f)
    }

    /** Task 3.6. */
    @Test fun aForgottenPin_saysWhoAndWhatHappens_withAWayBack_andTheAddressesAreFixed() {
        show(SetupUiState(unlockServerId = "aaaaaaaaaaaaaaaa", unlockName = "kind", username = "kind", public = "https://abs.example.org"))
        rule.onNodeWithText("Remove the protection").assertExists()
        rule.onNodeWithText("Sign in as kind. The protection of this profile is then removed and you switch to it.").assertExists()
        rule.onNodeWithTag("setupLocal").assertIsNotEnabled()
        rule.onNodeWithTag("setupPublic").assertIsNotEnabled()
        rule.onNodeWithTag("ssidCheckbox").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    /** I1 of Task 2: the words of a refusal come on the node that was there before. */
    @Test fun anotherAccount_isSaid_onTheLineThatWasThere() {
        var state by mutableStateOf(SetupUiState(unlockServerId = "aaaaaaaaaaaaaaaa", unlockName = "kind"))
        rule.setContent { LauschigTheme { SetupScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        val line = rule.onNodeWithTag("setupError")
        val before = line.fetchSemanticsNode().id
        state = state.copy(error = SetupError.UNLOCK_OTHER_ACCOUNT)
        rule.waitForIdle()
        assertEquals(before, line.fetchSemanticsNode().id)
        assertEquals(listOf("That is a different account. Nothing has changed."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    // A 20-character password on a slow tablet: the user must be able to see what landed in the field.
    private val secret = "kein-echtes-Passwort"

    private val dots = "\u2022".repeat(secret.length)

    /**
     * What the field draws: dots while hidden, the password itself when shown. Not the Password flag:
     * Compose Foundation 1.12.1 never updates it once the field exists (no store of isPassword in
     * CoreTextFieldSemanticsModifierNode.updateNodeSemantics), so it stays set while the text is shown.
     */
    private fun SemanticsNodeInteraction.drawn(): String = fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun SemanticsNodeInteraction.isHidden() = drawn() == dots

    private fun SemanticsNodeInteraction.isShown() = drawn() == secret

    @Test fun thePassword_isHiddenAtFirst_andCanBeShownAndHiddenAgain() {
        show(SetupUiState(password = secret))
        val field = rule.onNodeWithTag("setupPassword")
        assertTrue(field.isHidden())
        rule.onNodeWithContentDescription("Hide password").assertDoesNotExist()

        rule.onNodeWithContentDescription("Show password").performClick()
        assertTrue(field.isShown())
        rule.onNodeWithContentDescription("Show password").assertDoesNotExist()

        rule.onNodeWithContentDescription("Hide password").performClick()
        assertTrue(field.isHidden())
    }

    /** Back from another page (the navigation keeps saveable state there): hidden again. */
    @Test fun leavingTheForm_hidesThePasswordAgain() {
        var here by mutableStateOf(true)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            LauschigTheme {
                if (here) holder.SaveableStateProvider("setup") { SetupScreen(SetupUiState(password = secret), {}, {}, {}, {}, {}, {}, {}, {}, {}) }
                else Text("elsewhere")
            }
        }
        rule.onNodeWithContentDescription("Show password").performClick()
        assertTrue(rule.onNodeWithTag("setupPassword").isShown())

        here = false
        rule.waitForIdle()
        here = true
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("setupPassword").isHidden())
    }

    /** Turning the tablet or the process coming back: nothing about the password is saved, it is hidden again. */
    @Test fun aShownPassword_isNotSaved() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { LauschigTheme { SetupScreen(SetupUiState(password = secret), {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithContentDescription("Show password").performClick()
        assertTrue(rule.onNodeWithTag("setupPassword").isShown())

        restoration.emulateSavedInstanceStateRestore()
        assertTrue(rule.onNodeWithTag("setupPassword").isHidden())
    }
}
