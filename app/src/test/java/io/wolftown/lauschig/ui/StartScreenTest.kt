package io.wolftown.lauschig.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.common.ItemSummary
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

    private fun summary(id: String, title: String) =
        ItemSummary("s", id, title, "Author", null, null, null, 0.4f, true, false)

    @Test
    fun empty_showsHint() {
        rule.setContent { LauschigTheme { StartScreen(StartUiState(), onOpen = {}, onPlay = {}) } }
        rule.onNodeWithText("Nothing here yet – download a book to listen offline").assertIsDisplayed()
    }

    @Test
    fun rows_showSections_tapOpensDetails_playButtonPlays_longPressOpensMenu() {
        val calls = mutableListOf<String>()
        val state = StartUiState(
            continueItem = summary("a", "Book A"),
            downloaded = listOf(summary("b", "Book B")),
        )
        rule.setContent {
            LauschigTheme { StartScreen(state, onOpen = { calls += "open:$it" }, onPlay = { calls += "play:$it" }, onMenu = { calls += "menu:${it.id}" }) }
        }
        rule.onNodeWithText("Continue listening").assertIsDisplayed()
        rule.onNodeWithText("Downloaded").assertIsDisplayed()
        rule.onNodeWithText("Book A").performClick()
        rule.onNodeWithContentDescription("Play Book B").performClick()
        rule.onNodeWithText("Book B").performTouchInput { longClick() }
        assertEquals(listOf("open:a", "play:b", "menu:b"), calls)
    }

    @Test
    fun downloadingTile_showsBadge() {
        val state = StartUiState(continueItem = summary("a", "Book A").copy(isDownloaded = false, isDownloading = true))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}) } }
        rule.onNodeWithContentDescription("Downloading").assertIsDisplayed()
    }
}
