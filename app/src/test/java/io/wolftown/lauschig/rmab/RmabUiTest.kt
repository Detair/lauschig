package io.wolftown.lauschig.rmab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.wolftown.lauschig.external.AudibleVolume
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RmabUiTest {
    @get:Rule val rule = createComposeRule()
    private val volume = AudibleVolume("B1", "Band Drei", authors = listOf("Ann"))

    private fun show(state: RmabUiState, onRequest: (AudibleVolume) -> Unit = {}) =
        rule.setContent { LauschigTheme { RmabMissingActions(volume, state, onRequest) } }

    @Test fun missingSheet_withRmab_confirmBeforeRequest() {
        val requested = mutableListOf<String>()
        show(RmabUiState(connected = true)) { requested += it.asin }
        rule.onNodeWithText("Request").assertIsDisplayed().performClick()
        assertEquals("nothing is sent before the confirmation", emptyList<String>(), requested)
        rule.onNodeWithText("Request “Band Drei” from ReadMeABook?").assertIsDisplayed()
        rule.onNodeWithTag("rmabConfirm").performClick()
        assertEquals(listOf("B1"), requested)
        rule.onNodeWithText("Request “Band Drei” from ReadMeABook?").assertDoesNotExist()
    }

    @Test fun confirmDialog_cancel_sendsNothing() {
        val requested = mutableListOf<String>()
        show(RmabUiState(connected = true)) { requested += it.asin }
        rule.onNodeWithTag("rmabRequest").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), requested)
    }

    @Test fun withoutRmab_noButton() {
        show(RmabUiState(connected = false))
        rule.onNodeWithTag("rmabRequest").assertDoesNotExist()
        rule.onNodeWithTag("rmabStatus").assertDoesNotExist()
    }

    @Test fun statusLabels_andFailedCanBeRequestedAgain() {
        show(RmabUiState(true, mapOf("B1" to RmabStatus.WAITING)))
        rule.onNodeWithText("Requested · waiting").assertIsDisplayed()
        rule.onNodeWithTag("rmabRequest").assertDoesNotExist()
    }

    @Test fun failed_showsRequestButtonAgain() {
        show(RmabUiState(true, mapOf("B1" to RmabStatus.FAILED)))
        rule.onNodeWithTag("rmabRequest").assertIsDisplayed()
    }

    @Test fun settings_notConnected_showsConnect_connectedShowsUserAndHint() {
        var state by androidx.compose.runtime.mutableStateOf(RmabSettingsState())
        var opened = 0
        rule.setContent {
            LauschigTheme { RmabSettingsContent(state, { opened++ }, {}, {}, { _, _, _, _ -> }, {}, {}) }
        }
        rule.onNodeWithTag("rmabConnect").performClick()
        assertEquals(1, opened)
        state = RmabSettingsState(config = RmabConfig("https://r/", "fabi", needsReconnect = true))
        rule.onNodeWithText("Connected as fabi").assertIsDisplayed()
        rule.onNodeWithText("Not connected anymore — reconnect").assertIsDisplayed()
    }

    private fun outcomeState(m: RmabMessage, error: Boolean) =
        RmabUiState(true, outcomes = mapOf("B1" to RmabOutcome(m, error)))

    @Test fun request_failure_showsMessageInSheet() {
        show(outcomeState(RmabMessage(io.wolftown.lauschig.R.string.rmab_msg_failed, "HTTP 500"), true))
        rule.onNodeWithText("Request failed: HTTP 500").assertIsDisplayed()
        rule.onNodeWithTag("rmabOutcomeError").assertIsDisplayed()
        rule.onNodeWithTag("rmabRequest").assertIsDisplayed()
    }

    @Test fun request_noAuthor_showsMessageInSheet() {
        show(outcomeState(RmabMessage(io.wolftown.lauschig.R.string.rmab_msg_no_author), true))
        rule.onNodeWithText("Cannot request: Audible lists no author for this volume.").assertIsDisplayed()
    }

    @Test fun request_notConnected_andUnreachable_showMessage() {
        show(outcomeState(RmabMessage(io.wolftown.lauschig.R.string.rmab_msg_unreachable), true))
        rule.onNodeWithText("Could not reach ReadMeABook.").assertIsDisplayed()
    }

    @Test fun request_success_messageNextToStatus() {
        show(RmabUiState(true, mapOf("B1" to RmabStatus.WAITING), mapOf("B1" to RmabOutcome(RmabMessage(io.wolftown.lauschig.R.string.rmab_msg_created, "Band Drei"), false))))
        rule.onNodeWithText("Requested “Band Drei”").assertIsDisplayed()
        rule.onNodeWithTag("rmabOutcome").assertIsDisplayed()
    }

    /** The same eye as on the sign-in form; for a token it says token. */
    @Test fun connectDialog_secretCanBeShown_andTheEyeNamesWhatItShows() {
        rule.setContent {
            LauschigTheme { RmabSettingsContent(RmabSettingsState(dialogOpen = true), {}, {}, {}, { _, _, _, _ -> }, {}, {}) }
        }
        val secret = rule.onNodeWithTag("rmabSecret")
        secret.performTextInput("kein-echtes-Passwort")
        fun drawn() = secret.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("\u2022".repeat(20), drawn())

        rule.onNodeWithContentDescription("Show password").performClick()
        assertEquals("kein-echtes-Passwort", drawn())

        rule.onNodeWithContentDescription("Hide password").performClick()
        rule.onNodeWithTag("rmabToggleMode").performClick()
        rule.onNodeWithContentDescription("Show token").assertExists()
        rule.onNodeWithContentDescription("Show password").assertDoesNotExist()
    }
}
