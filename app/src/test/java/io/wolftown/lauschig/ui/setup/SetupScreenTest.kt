package io.wolftown.lauschig.ui.setup

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
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
}
