package io.wolftown.lauschig.tv.browse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.common.TAG_CARD
import io.wolftown.lauschig.tv.common.cardTag
import io.wolftown.lauschig.tv.common.tvItem
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.search.SearchUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvSearchScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val found = SearchUiState(
        query = "drei", resultsFor = "drei", searched = true,
        titles = listOf(tvItem("t1"), tvItem("t2")),
        series = listOf(Facet("Die drei", 3, "t1")),
        authors = listOf(Facet("Anna Author", 2, null)),
        narrators = listOf(Facet("Nick Narrator", 5, "t2")),
    )

    private val calls = mutableListOf<String>()

    private fun show(state: SearchUiState) = rule.setContent { TvTheme { Screen(state) } }

    @androidx.compose.runtime.Composable
    private fun Screen(state: SearchUiState, onQuery: (String) -> Unit = { calls += "query:$it" }) = TvSearchScreen(
        state, onQuery = onQuery, onSubmit = { calls += "submit" },
        onOpenItem = { calls += "item:$it" }, onOpenBrowse = { kind, name -> calls += "browse:$kind:$name" },
    )

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private val text get() = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(TAG_SEARCH_FIELD)))

    @Test fun theFocus_startsOnTheField_withoutTheKeyboard() {
        show(SearchUiState())
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
        text.assertIsNotFocused()
    }

    @Test fun ok_typesInTheField_andTheSearchKeySubmits() {
        var state by mutableStateOf(SearchUiState())
        rule.setContent { TvTheme { Screen(state, onQuery = { calls += "query:$it"; state = state.copy(query = it) }) } }
        press(Key.DirectionCenter)
        text.assertIsFocused()
        text.performTextInput("drei")
        assertEquals(listOf("query:drei"), calls)
        text.performImeAction()
        assertEquals(listOf("query:drei", "submit"), calls)
        // Back on the field as a whole: the keyboard has gone, and down leads on.
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
        text.assertIsNotFocused()
    }

    @Test fun beforeASearch_theRecentOnesAreButtons_andOkSearchesAgain() {
        var state by mutableStateOf(SearchUiState(recent = listOf("drei", "witcher")))
        rule.setContent { TvTheme { Screen(state, onQuery = { calls += "query:$it"; state = found.copy(query = it) }) } }
        rule.onNodeWithText(context.getString(R.string.search_recent)).assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_RECENT + "drei").assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_RECENT + "witcher").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("query:witcher", "submit"), calls)
        // The buttons give way to the results; the focus waits on the field.
        rule.onNodeWithTag(TAG_RECENT + "witcher").assertDoesNotExist()
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
    }

    @Test fun theResults_areRows_andOkOpensTheRightPage() {
        show(found)
        rule.onNodeWithTag(TAG_SEARCH_ROW + "titles").assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("item:t2"), calls)
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CARD + "series:Die drei").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CARD + "authors:Anna Author").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CARD + "narrators:Nick Narrator").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(
            listOf("item:t2", "browse:${BrowseKind.SERIES}:Die drei", "browse:${BrowseKind.AUTHOR}:Anna Author", "browse:${BrowseKind.NARRATOR}:Nick Narrator"),
            calls,
        )
        press(Key.DirectionUp, times = 4)
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
    }

    @Test fun emptyRows_areLeftOut() {
        show(found.copy(series = emptyList(), narrators = emptyList()))
        rule.onNodeWithTag(TAG_SEARCH_ROW + "series").assertDoesNotExist()
        rule.onNodeWithTag(TAG_SEARCH_ROW + "narrators").assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.search_series)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_SEARCH_ROW + "authors").assertExists()
    }

    @Test fun noHits_sayso() {
        show(SearchUiState(query = "xyz", resultsFor = "xyz", searched = true))
        rule.onNodeWithTag(TAG_SEARCH_EMPTY).assertExists()
        rule.onNodeWithText(context.getString(R.string.search_nothing_found, "xyz")).assertExists()
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
    }

    /** Genres and tags are not on the TV: hits only there count as none. */
    @Test fun hitsOnlyInGenres_countAsNone() {
        show(SearchUiState(query = "krimi", resultsFor = "krimi", searched = true, genres = listOf(Facet("Krimi", 4, null))))
        rule.onNodeWithTag(TAG_SEARCH_EMPTY).assertExists()
    }

    @Test fun whileTheFirstSearchRuns_itSaysSo() {
        show(SearchUiState(query = "dr"))
        rule.onNodeWithTag(TAG_SEARCH_SEARCHING).assertExists()
        rule.onNodeWithTag(TAG_SEARCH_EMPTY).assertDoesNotExist()
    }

    /** Review focus 1: the focused row's card goes while results reload; the focus stays in the row. */
    @Test fun theFocusedCardGoes_theFocusStaysInItsRow() {
        var state by mutableStateOf(found)
        rule.setContent { TvTheme { Screen(state) } }
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("t2")).assertIsFocused()
        state = found.copy(titles = listOf(tvItem("t1")))
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
    }

    /** As after Back from a result: the page comes again, and the focus is on the card it was on. */
    @Test fun comingBack_landsOnTheResultItLeftFrom() {
        var shown by mutableStateOf(true)
        rule.setContent {
            val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            TvTheme { if (shown) pages.SaveableStateProvider("search") { Screen(found) } }
        }
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("t2")).assertIsFocused()
    }

    @Test fun comingBack_fromTheField_landsOnTheField() {
        var shown by mutableStateOf(true)
        rule.setContent {
            val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            TvTheme { if (shown) pages.SaveableStateProvider("search") { Screen(found) } }
        }
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SEARCH_FIELD).assertIsFocused()
    }
}
