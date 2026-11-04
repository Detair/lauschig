package io.wolftown.lauschig.ui

import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.start.StartScreen
import io.wolftown.lauschig.ui.start.StartUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        var library = 0
        rule.setContent { LauschigTheme { StartScreen(StartUiState(), onOpen = {}, onPlay = {}, onOpenLibrary = { library++ }) } }
        rule.onNodeWithText("Nothing listened to yet").assertIsDisplayed()
        rule.onNodeWithText("Start a title in the library, and you can pick it up again here.").assertIsDisplayed()
        rule.onNodeWithText("Go to library").performClick()
        assertEquals(1, library)
    }

    @Test
    fun loading_showsNeitherSectionsNorTheEmptyHint() {
        rule.setContent { LauschigTheme { StartScreen(StartUiState(loading = true), onOpen = {}, onPlay = {}) } }
        rule.onNodeWithText("Nothing listened to yet").assertDoesNotExist()
        rule.onNodeWithTag("loadingState").assertExists()
    }

    @Test
    fun rows_showSections_tapOpensDetails_playButtonPlays_longPressOpensMenu() {
        val calls = mutableListOf<String>()
        val state = StartUiState(
            continueItems = listOf(summary("a", "Book A")),
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
        // The second "continue" title: the first one is the card above the row.
        val state = StartUiState(continueItems = listOf(summary("x", "Book X"), summary("a", "Book A").copy(isDownloaded = false, isDownloading = true)))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}) } }
        rule.onNodeWithContentDescription("Downloading").assertIsDisplayed()
    }

    @Test
    fun hero_isTheFirstContinueTitle_theOthersStayInTheRow() {
        val calls = mutableListOf<String>()
        val state = StartUiState(
            continueItems = listOf(summary("a", "Book A").copy(remainingSec = 51 * 60.0), summary("b", "Book B")),
        )
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = { calls += "open:$it" }, onPlay = { calls += "play:$it" }, onPlayPause = { calls += "toggle:$it" }) } }
        val hero = rule.onNodeWithTag("continueHero")
        hero.assertIsDisplayed()
        rule.onNode(hasTestTag("heroLeft"), useUnmergedTree = true).assertTextEquals("51 min left")
        rule.onNode(hasText("Book A") and hasAnyAncestor(hasTestTag("continueHero"))).assertExists()
        // Book B is a tile of the row, not in the card.
        rule.onNode(hasText("Book B") and hasAnyAncestor(hasTestTag("continueHero"))).assertDoesNotExist()
        rule.onNodeWithContentDescription("Play Book B").assertExists()
        rule.onNodeWithTag("heroPlay").performClick()
        rule.onNodeWithText("Book A").performClick()
        assertEquals(listOf("toggle:a", "open:a"), calls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "de")
    fun hero_german_saysWhatIsLeft() {
        val state = StartUiState(continueItems = listOf(summary("a", "Book A").copy(remainingSec = 51 * 60.0 + 20)))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}) } }
        rule.onNodeWithTag("heroLeft", useUnmergedTree = true).assertTextEquals("noch 51 Min.")
        rule.onNodeWithText("Weiterhören").assertIsDisplayed()
        rule.onNodeWithText("Alle").assertIsDisplayed()
    }

    @Test
    fun hero_showsPause_whileItsTitlePlays() {
        val state = StartUiState(continueItems = listOf(summary("a", "Book A")))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}, playingId = "a") } }
        rule.onNodeWithTag("heroPlay").assertContentDescriptionEquals("Pause")
    }

    @Test
    fun hero_withoutRemainingTime_showsThePercentage() {
        rule.setContent { LauschigTheme { StartScreen(StartUiState(continueItems = listOf(summary("a", "Book A"))), onOpen = {}, onPlay = {}) } }
        rule.onNodeWithTag("heroLeft", useUnmergedTree = true).assertTextEquals("40 %")
    }

    @Test
    fun noContinueTitle_noHero() {
        val state = StartUiState(recentlyAdded = listOf(summary("r", "Book R")), downloaded = listOf(summary("b", "Book B")))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}) } }
        rule.onNodeWithTag("continueHero").assertDoesNotExist()
        rule.onNodeWithText("Continue listening").assertDoesNotExist()
        rule.onNodeWithText("New on the server").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring, so the lines are real
    fun hero_atDoubleFontSize_wrapsInsteadOfClipping_andKeepsItsButtonTarget() {
        val title = "Die drei ??? und der Super-Papagei"
        val state = StartUiState(continueItems = listOf(summary("a", title).copy(remainingSec = 3_900.0)))
        rule.setContent { LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}) } }
        val play = rule.onNodeWithTag("heroPlay").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).fetchSemanticsNode().boundsInRoot
        val left = rule.onNodeWithTag("heroLeft", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("\"left\" text runs under the button: $left / $play", left.right <= play.left)
        // The title wraps to its two lines (ellipsized after them), and the card grows with it instead of cutting it off.
        val titleNode = rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        titleNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(2, layouts.single().lineCount)
        val card = rule.onNodeWithTag("continueHero").fetchSemanticsNode().boundsInRoot
        assertTrue(titleNode.boundsInRoot.bottom <= left.top && left.bottom <= card.bottom)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h2400dp")
    fun startScreen_sectionsInOrder_emptyOnesHidden() {
        var library = 0
        var discover = 0
        val state = StartUiState(
            continueItems = listOf(summary("a", "Book A")),
            recentlyAdded = listOf(summary("r", "Book R")),
            nextInSeries = listOf(summary("n", "Book N")),
        )
        rule.setContent {
            LauschigTheme { StartScreen(state, onOpen = {}, onPlay = {}, onOpenLibrary = { library++ }, onOpenDiscover = { discover++ }) }
        }
        val y = listOf("Continue listening", "Next in your series", "New on the server").map {
            rule.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(y.sorted(), y)
        rule.onNodeWithText("Downloaded").assertDoesNotExist()
        rule.onNodeWithTag("seeAll-continue").performClick()
        rule.onNodeWithTag("seeAll-next").assertDoesNotExist()
        rule.onNodeWithTag("seeAll-recent").performClick()
        assertEquals(1, library)
        assertEquals(1, discover)
    }
}
