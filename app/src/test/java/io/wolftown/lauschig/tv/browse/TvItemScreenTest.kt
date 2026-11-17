package io.wolftown.lauschig.tv.browse

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.common.TAG_MESSAGE
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.detail.ItemDetailUiState
import io.wolftown.lauschig.ui.detail.PrimaryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvItemScreenTest {
    // An activity: Back goes through its dispatcher to the chapter list's handler.
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val chapters = listOf(
        ChapterRow(0, "One", 600.0, isCurrent = false),
        ChapterRow(1, "Two", 600.0, isCurrent = true, positionInChapterSec = 30.0),
        ChapterRow(2, "Three", 600.0, isCurrent = false),
    )
    private val book = ItemDetailUiState(
        found = true, itemId = "b1", title = "The Book", author = "Anna Author", narrator = "Nick Narrator",
        seriesName = "Die drei", seriesSequence = "3", durationSec = 1800.0, description = "Short.",
        primary = PrimaryAction.Continue(2), chapters = chapters, online = true,
    )

    private val calls = mutableListOf<String>()

    private fun show(state: ItemDetailUiState, isPodcast: Boolean = false, online: Boolean = true) = rule.setContent {
        TvTheme { Screen(state, isPodcast, online) }
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: ItemDetailUiState, isPodcast: Boolean = false, online: Boolean = true) = TvItemScreen(
        state, isPodcast,
        onPrimary = { calls += "primary" }, onFromStart = { calls += "fromStart" }, onChapter = { calls += "chapter:$it" },
        onOpenSeries = { calls += "series:$it" }, online = online, onRetry = { calls += "retry" },
    )

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun theFocus_startsOnTheMainButton_whichSaysWhatItDoes() {
        show(book)
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.detail_continue_chapter, 2)).assertExists()
        rule.onNodeWithText("Anna Author").assertExists()
        rule.onNodeWithText(context.getString(R.string.detail_quick_narrator, "Nick Narrator")).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("primary"), calls)
    }

    @Test fun fromStart_isBesideIt_andStarts() {
        show(book)
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_FROM_START).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("fromStart"), calls)
    }

    @Test fun withoutProgress_thereIsNoFromStart() {
        show(book.copy(primary = PrimaryAction.Play))
        rule.onNodeWithText(context.getString(R.string.detail_play)).assertExists()
        rule.onNodeWithTag(TAG_FROM_START).assertDoesNotExist()
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_CHAPTERS).assertIsFocused()
    }

    @Test fun listenAgain_hasFromStart_too() {
        show(book.copy(primary = PrimaryAction.ListenAgain))
        rule.onNodeWithText(context.getString(R.string.detail_listen_again)).assertExists()
        rule.onNodeWithTag(TAG_FROM_START).assertExists()
    }

    @Test fun chapters_openOnTheCurrentOne_andOkPlaysIt() {
        show(book)
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(TAG_CHAPTERS).assertIsFocused()
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CHAPTER + 1).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CHAPTER + 2).assertIsFocused()
        // The list keeps the focus: nothing to its left takes it.
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_CHAPTER + 2).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("chapter:2"), calls)
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
    }

    @Test fun back_closesTheChapters_andTheFocusReturnsToTheirButton() {
        show(book)
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHAPTER_LIST).assertDoesNotExist()
        rule.onNodeWithTag(TAG_CHAPTERS).assertIsFocused()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun withoutChapters_thereIsNoChapterButton() {
        show(book.copy(chapters = emptyList()))
        rule.onNodeWithTag(TAG_CHAPTERS).assertDoesNotExist()
    }

    @Test fun up_reachesTheSeries_andOkOpensIt() {
        show(book)
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_SERIES_LINE).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.detail_series_line, context.getString(R.string.detail_series_band, "Die drei", "3"))).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf("series:Die drei"), calls)
    }

    @Test fun aLongDescription_rollsWithTheRemote_andUpLeavesItAtTheTop() {
        val long = (1..80).joinToString("\n") { "Line $it of a long description." }
        show(book.copy(description = long))
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_DESCRIPTION).assertIsFocused()
        press(Key.DirectionDown, times = 2)
        rule.onNodeWithTag(TAG_DESCRIPTION).assertIsFocused()
        assertTrue("rolled", scrollOf(TAG_DESCRIPTION) > 0f)
        press(Key.DirectionUp, times = 2)
        rule.onNodeWithTag(TAG_DESCRIPTION).assertIsFocused()
        assertEquals(0f, scrollOf(TAG_DESCRIPTION))
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused()
    }

    @Test fun aShortDescription_isNoStop() {
        show(book)
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused()
    }

    /** Review focus 2: offline the TV cannot play; the buttons are off, the hint says why, and "Try again" has the focus. */
    @Test fun offline_buttonsOff_hint_andRetry() {
        show(book, online = false)
        rule.onNodeWithTag(TAG_OFFLINE).assertExists()
        rule.onNodeWithTag(TAG_ITEM_RETRY).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("retry"), calls)
        rule.onNodeWithTag(TAG_PRIMARY).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_FROM_START).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_CHAPTERS).assertIsNotEnabled()
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertEquals(listOf("retry"), calls)
    }

    @Test fun offline_aTitleThePlayerHolds_playsOn() {
        show(book.copy(playableOffline = true), online = false)
        rule.onNodeWithTag(TAG_OFFLINE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused().assertIsEnabled()
    }

    @Test fun theServerComesBack_theFocusMovesFromRetryToTheMainButton() {
        var online by mutableStateOf(false)
        rule.setContent { TvTheme { Screen(book, online = online) } }
        rule.onNodeWithTag(TAG_ITEM_RETRY).assertIsFocused()
        online = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_ITEM_RETRY).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused().assertIsEnabled()
    }

    @Test fun aPodcast_showsOnlyTheHint() {
        show(book, isPodcast = true)
        rule.onNodeWithText(context.getString(R.string.tv_item_podcast)).assertIsFocused()
        rule.onNodeWithTag(TAG_PRIMARY).assertDoesNotExist()
        rule.onNodeWithText("The Book").assertDoesNotExist()
    }

    @Test fun notFound_saysSo() {
        show(ItemDetailUiState(itemId = "b1"))
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.detail_not_found)).assertExists()
    }

    /** As it happens: the page shows before Room has answered; the content then takes the focus. */
    @Test fun whenTheTitleArrives_theFocusGoesToTheMainButton() {
        var state by mutableStateOf(ItemDetailUiState(itemId = "b1"))
        rule.setContent { TvTheme { Screen(state) } }
        state = book
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PRIMARY).assertIsFocused()
    }

    private fun scrollOf(tag: String): Float {
        // The rolled text is the description's only scrollable child.
        val node = rule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(tag)),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        return node.config[SemanticsProperties.VerticalScrollAxisRange].value()
    }
}
