package io.wolftown.lauschig.tv.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
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
import io.wolftown.lauschig.tv.common.TAG_CARD
import io.wolftown.lauschig.tv.common.TAG_MESSAGE
import io.wolftown.lauschig.tv.common.TAG_MESSAGE_ACTION
import io.wolftown.lauschig.tv.common.cardTag
import io.wolftown.lauschig.tv.common.tvItem
import io.wolftown.lauschig.ui.discover.SeriesTile
import io.wolftown.lauschig.ui.start.StartUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvHomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val full = StartUiState(
        continueItems = listOf(tvItem("c1"), tvItem("c2")),
        nextInSeries = listOf(tvItem("n1")),
        recentlyAdded = listOf(tvItem("r1"), tvItem("r2")),
    )
    private val series = listOf(SeriesTile("Die drei", 3, tvItem("s1")), SeriesTile("Witcher", 2, null))

    private val openedItems = mutableListOf<String>()
    private val openedSeries = mutableListOf<String>()
    private var retries = 0

    private fun show(state: StartUiState, tiles: List<SeriesTile> = series, online: Boolean = true) = rule.setContent {
        TvTheme { TvHomeScreen(state, tiles, online, onRetry = { retries++ }, onOpenItem = { openedItems += it }, onOpenSeries = { openedSeries += it }) }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun theFocus_startsOnTheFirstContinueCard() {
        show(full)
        rule.onNodeWithTag(cardTag("c1")).assertIsFocused()
    }

    @Test fun withoutContinue_itStartsOnTheFirstRowThereIs() {
        show(full.copy(continueItems = emptyList()))
        rule.onNodeWithTag(cardTag("n1")).assertIsFocused()
    }

    @Test fun emptyRows_haveNoHeading() {
        show(full.copy(nextInSeries = emptyList()), tiles = emptyList())
        rule.onNodeWithText(context.getString(R.string.start_continue)).assertExists()
        rule.onNodeWithText(context.getString(R.string.start_next_in_series)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.discover_series)).assertDoesNotExist()
        rule.onNodeWithTag(TAG_ROW + "next").assertDoesNotExist()
    }

    @Test fun down_changesTheRow_andOkOpensTheTitle() {
        show(full)
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("c2")).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("n1")).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("r1")).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("r1"), openedItems)
    }

    @Test fun upAgain_landsOnTheCardTheRowRemembers() {
        show(full)
        press(Key.DirectionRight)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        rule.onNodeWithTag(cardTag("c2")).assertIsFocused()
    }

    @Test fun okOnASeries_opensIt() {
        show(full.copy(continueItems = emptyList(), nextInSeries = emptyList(), recentlyAdded = emptyList()))
        rule.onNodeWithTag(TAG_CARD + "Die drei").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("Witcher"), openedSeries)
        assertEquals(emptyList<String>(), openedItems)
    }

    @Test fun loading_saysSo() {
        show(StartUiState(loading = true))
        rule.onNodeWithText(context.getString(R.string.tv_home_loading)).assertExists()
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
    }

    @Test fun anEmptyLibrary_saysSo() {
        show(StartUiState(), tiles = emptyList())
        rule.onNodeWithText(context.getString(R.string.tv_home_empty)).assertIsFocused()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertDoesNotExist()
    }

    /** Review focus 2: no server, nothing stored, and a way to try again. */
    @Test fun offlineWithoutData_saysSo_andRetries() {
        show(StartUiState(), tiles = emptyList(), online = false)
        rule.onNodeWithText(context.getString(R.string.tv_home_offline)).assertExists()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, retries)
    }

    @Test fun offlineWithData_showsTheRows() {
        show(full, online = false)
        rule.onNodeWithTag(cardTag("c1")).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.tv_home_offline)).assertDoesNotExist()
    }

    @Test fun whenTheLibraryArrives_theFocusMovesFromTheMessageToTheFirstCard() {
        var state by mutableStateOf(StartUiState(loading = true))
        rule.setContent { TvTheme { TvHomeScreen(state, emptyList(), true, {}, {}, {}) } }
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
        state = full
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("c1")).assertIsFocused()
    }

    /** Review focus 1: the row with the focus is emptied; the focus lands on the row now in its place. */
    @Test fun theFocusedRowGoes_theFocusMovesToTheRowInItsPlace() {
        var state by mutableStateOf(full)
        rule.setContent { TvTheme { TvHomeScreen(state, series, true, {}, {}, {}) } }
        press(Key.DirectionDown)
        rule.onNodeWithTag(cardTag("n1")).assertIsFocused()
        state = full.copy(nextInSeries = emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("r1")).assertIsFocused()
    }

    /** Review focus 1: the last row, with the focus, is emptied; the focus lands on the row above. */
    @Test fun theFocusedLastRowGoes_theFocusMovesUp() {
        var state by mutableStateOf(full)
        rule.setContent { TvTheme { TvHomeScreen(state, emptyList(), true, {}, {}, {}) } }
        press(Key.DirectionDown, times = 2)
        rule.onNodeWithTag(cardTag("r1")).assertIsFocused()
        state = full.copy(recentlyAdded = emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("n1")).assertIsFocused()
    }

    /** Review focus 1: the row with the focus reloads and gets shorter. */
    @Test fun theFocusedRowShrinks_theFocusStaysInIt() {
        var state by mutableStateOf(full)
        rule.setContent { TvTheme { TvHomeScreen(state, emptyList(), true, {}, {}, {}) } }
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("c2")).assertIsFocused()
        state = full.copy(continueItems = listOf(tvItem("c1")))
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("c1")).assertIsFocused()
    }
}
