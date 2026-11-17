package io.wolftown.lauschig.tv.setup

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.ui.setup.SetupError
import io.wolftown.lauschig.ui.setup.SetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvSetupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val filled = SetupUiState(public = "https://abs.example.org", username = "anna", password = "secret")

    private var logins = 0
    private var confirmed = 0
    private var dismissed = 0

    private fun show(state: SetupUiState) = rule.setContent {
        TvTheme {
            TvSetupScreen(
                state = state, onLocal = {}, onPublic = {}, onUsername = {}, onPassword = {},
                onLogin = { logins++ }, onConfirmCleartext = { confirmed++ }, onDismissCleartext = { dismissed++ },
            )
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /** The dialog is a window of its own with its own focus: keys go to its focused node there. */
    private fun pressInDialog(key: Key) {
        rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun focus_startsInTheFirstField() {
        show(SetupUiState())
        rule.onNodeWithTag(TAG_LOCAL).assertIsFocused()
    }

    @Test fun focus_startsInTheFirstEmptyField() {
        show(SetupUiState(public = "https://abs.example.org", username = "anna"))
        rule.onNodeWithTag(TAG_PASSWORD).assertIsFocused()
    }

    @Test fun downFourTimes_reachesSignIn_andUpGoesBack() {
        show(SetupUiState())
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_PUBLIC).assertIsFocused()
        press(Key.DirectionDown, times = 3)
        rule.onNodeWithTag(TAG_LOGIN).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_PASSWORD).assertIsFocused()
    }

    @Test fun ok_putsTheFocusIntoTheText_andDownStillLeaves() {
        show(SetupUiState())
        press(Key.DirectionCenter)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TAG_LOCAL))).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_PUBLIC).assertIsFocused()
        // Only the whole field: moving on does not open the keyboard again.
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TAG_PUBLIC))).assertIsNotFocused()
    }

    @Test fun thePasswordCanBeShown_fromTheRight() {
        show(SetupUiState(public = "https://abs.example.org", username = "anna", password = "secret"))
        press(Key.DirectionUp) // from the button to the password
        rule.onNodeWithTag(TAG_PASSWORD).assertIsFocused()
        val passwordText = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TAG_PASSWORD)))
        passwordText.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("••••••")))
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_SHOW_PASSWORD).assertIsFocused()
        press(Key.DirectionCenter)
        passwordText.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("secret")))
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_LOGIN).assertIsFocused()
    }

    @Test fun upOnTheFirstField_keepsTheFocusThere() {
        show(SetupUiState())
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_LOCAL).assertIsFocused()
        // Also while typing in it.
        press(Key.DirectionCenter)
        press(Key.DirectionUp)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TAG_LOCAL))).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_PUBLIC).assertIsFocused()
    }

    @Test fun theField_isNamedForTalkBack() {
        show(SetupUiState(username = "anna"))
        rule.onNodeWithTag(TAG_LOCAL).assertContentDescriptionEquals(context.getString(R.string.setup_local_address))
        rule.onNodeWithTag(TAG_USERNAME).assertContentDescriptionEquals(context.getString(R.string.setup_username) + ": anna")
    }

    @Test fun signIn_isOffWithoutCanLogin_andOkDoesNothing() {
        show(SetupUiState())
        press(Key.DirectionDown, times = 4)
        rule.onNodeWithTag(TAG_LOGIN).assertIsFocused().assertIsNotEnabled()
        press(Key.DirectionCenter)
        assertEquals(0, logins)
    }

    @Test fun aFilledForm_startsOnSignIn_andOkLogsIn() {
        show(filled)
        rule.onNodeWithTag(TAG_LOGIN).assertIsFocused().assertIsEnabled()
        press(Key.DirectionCenter)
        assertEquals(1, logins)
    }

    @Test fun aWrongLogin_showsItsText() {
        show(filled.copy(error = SetupError.WRONG_LOGIN))
        // Read out when it appears: the line carries its words as its description (AnnouncedLine).
        rule.onNodeWithTag(TAG_ERROR).assertContentDescriptionEquals(context.getString(R.string.setup_error_wrong_login))
    }

    @Test fun fixedAddresses_haveNoAddressFields_andNameTheAccount() {
        show(SetupUiState(unlockServerId = "s", unlockName = "Anna"))
        rule.onNodeWithTag(TAG_LOCAL).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PUBLIC).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.setup_unlock_intro, "Anna")).assertExists()
        rule.onNodeWithTag(TAG_USERNAME).assertIsFocused()
    }

    @Test fun theCleartextQuestion_namesTheHosts_andConfirms() {
        show(filled.copy(cleartextPrompt = listOf("http://abs.example.org:13378", "http://audio.example.net")))
        rule.onNodeWithText("http://abs.example.org:13378", substring = true).assertExists()
        rule.onNodeWithText("http://audio.example.net", substring = true).assertExists()
        // The safe answer has the focus; the other one is a step to the right.
        rule.onNodeWithTag(TAG_CLEARTEXT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionRight)
        rule.onNodeWithTag(TAG_CLEARTEXT_CONFIRM).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test fun theCleartextQuestion_cancels() {
        show(filled.copy(cleartextPrompt = listOf("http://abs.example.org:13378")))
        rule.onNodeWithTag(TAG_CLEARTEXT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    /** As it happens: the question comes after "Sign in", over a form that already has the focus. */
    @Test fun theCleartextQuestion_arrivingLater_takesTheFocus() {
        var state by mutableStateOf(filled)
        rule.setContent {
            TvTheme {
                TvSetupScreen(
                    state = state, onLocal = {}, onPublic = {}, onUsername = {}, onPassword = {},
                    onLogin = { state = state.copy(cleartextPrompt = listOf("http://abs.example.org:13378")) },
                    onConfirmCleartext = { confirmed++ }, onDismissCleartext = { dismissed++; state = state.copy(cleartextPrompt = null) },
                )
            }
        }
        rule.onNodeWithTag(TAG_LOGIN).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CLEARTEXT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionRight)
        rule.onNodeWithTag(TAG_CLEARTEXT_CONFIRM).assertIsFocused()
        pressInDialog(Key.DirectionLeft)
        pressInDialog(Key.DirectionCenter)
        assertEquals(1, dismissed)
        rule.onNodeWithTag(TAG_CLEARTEXT_CANCEL).assertDoesNotExist()
    }

    @Test fun thereIsNeitherSsoNorScan() {
        show(SetupUiState(ssoAvailable = true, ssoButtonText = "Company login"))
        rule.onNodeWithText(context.getString(R.string.setup_sso_default)).assertDoesNotExist()
        rule.onNodeWithText("Company login").assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.server_qr_scan)).assertDoesNotExist()
        rule.onNodeWithTag("ssoButton").assertDoesNotExist()
        rule.onNodeWithTag("scanQr").assertDoesNotExist()
    }
}
