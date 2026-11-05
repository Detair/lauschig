package io.wolftown.lauschig.ui.setup

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
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

    @Test fun anErrorIsAnnounced() {
        show(SetupUiState(addProfile = true, error = SetupError.WRONG_LOGIN))
        rule.onNodeWithTag("setupError").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }
}
