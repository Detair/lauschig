package io.wolftown.lauschig.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.start.StartRow
import io.wolftown.lauschig.ui.start.StartScreen
import io.wolftown.lauschig.ui.start.StartUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun empty_showsHint() {
        rule.setContent { LauschigTheme { StartScreen(StartUiState(), onPlay = {}) } }
        rule.onNodeWithText("Nothing here yet – download a book to listen offline").assertIsDisplayed()
    }

    @Test
    fun rows_showSectionsAndTapPlays() {
        val played = mutableListOf<String>()
        val state = StartUiState(
            continueItem = StartRow("a", "Book A", "Author A", "42 %"),
            downloaded = listOf(StartRow("b", "Book B", "Author B", null)),
        )
        rule.setContent { LauschigTheme { StartScreen(state, onPlay = { played += it }) } }
        rule.onNodeWithText("Continue listening").assertIsDisplayed()
        rule.onNodeWithText("Downloaded").assertIsDisplayed()
        rule.onNodeWithText("Book A").performClick()
        rule.onNodeWithText("Book B").performClick()
        assertEquals(listOf("a", "b"), played)
    }
}
