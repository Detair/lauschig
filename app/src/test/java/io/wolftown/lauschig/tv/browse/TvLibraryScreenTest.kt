package io.wolftown.lauschig.tv.browse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
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
import io.wolftown.lauschig.ui.discover.DiscoverSort
import io.wolftown.lauschig.ui.discover.DiscoverUiState
import io.wolftown.lauschig.ui.discover.SeriesTile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvLibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val titles = (1..8).map { tvItem("t$it") }
    private val full = DiscoverUiState(all = titles, series = listOf(SeriesTile("Die drei", 3, titles[0]), SeriesTile("Witcher", 2, null)))

    private val opened = mutableListOf<String>()
    private val openedSeries = mutableListOf<String>()
    private val sorts = mutableListOf<DiscoverSort>()
    private val shown = mutableListOf<Boolean>()
    private var retries = 0

    private fun show(state: DiscoverUiState, showSeries: Boolean = false, online: Boolean = true, syncing: Boolean = false) = rule.setContent {
        TvTheme {
            TvLibraryScreen(
                state, showSeries, onShowSeries = { shown += it }, onSort = { sorts += it },
                onOpenItem = { opened += it }, onOpenSeries = { openedSeries += it },
                online = online, onRetry = { retries++ }, syncing = syncing,
            )
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun theFocus_startsOnTheFirstCard_andOkOpensIt() {
        show(full)
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("t2")).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("t2"), opened)
    }

    @Test fun down_movesThroughTheGrid() {
        show(full)
        press(Key.DirectionDown)
        // 960 dp less the page's margins hold five cards a row.
        rule.onNodeWithTag(cardTag("t6")).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
    }

    @Test fun up_reachesTheSwitch_andTheSort_andOkSorts() {
        show(full)
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_SHOW_TITLES).assertIsFocused().assertIsSelected()
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_SHOW_SERIES).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(TAG_SORT + DiscoverSort.TITLE.name).assertIsFocused().assertIsSelected()
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(TAG_SORT + DiscoverSort.ADDED.name).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(DiscoverSort.ADDED), sorts)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(DiscoverSort.ADDED, DiscoverSort.DURATION), sorts)
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assertExists()
    }

    @Test fun okOnSeries_switches() {
        show(full)
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf(true), shown)
    }

    @Test fun theSeries_showAsAGrid_andOkOpensOne_withoutASort() {
        show(full, showSeries = true)
        rule.onNodeWithTag(TAG_CARD + "Die drei").assertIsFocused()
        rule.onNodeWithTag(TAG_SORT + DiscoverSort.TITLE.name).assertDoesNotExist()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("Witcher"), openedSeries)
        press(Key.DirectionUp)
        rule.onNodeWithTag(TAG_SHOW_SERIES).assertIsFocused().assertIsSelected()
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf(false), shown)
    }

    /** Switching with the control above keeps the focus on it: the new grid does not take it. */
    @Test fun switching_leavesTheFocusOnTheSwitch() {
        var series by mutableStateOf(false)
        rule.setContent { TvTheme { TvLibraryScreen(full, series, { series = it }, {}, {}, {}) } }
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SHOW_SERIES).assertIsFocused()
        rule.onNodeWithTag(TAG_CARD + "Die drei").assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_CARD + "Die drei").assertIsFocused()
    }

    @Test fun withoutSeries_theSwitchSaysSo_andKeepsTheFocus() {
        var series by mutableStateOf(false)
        rule.setContent { TvTheme { TvLibraryScreen(full.copy(series = emptyList()), series, { series = it }, {}, {}, {}) } }
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_LIBRARY_NO_SERIES).assertExists()
        rule.onNodeWithTag(TAG_SHOW_SERIES).assertIsFocused()
    }

    @Test fun loading_saysSo() {
        show(DiscoverUiState(loading = true))
        rule.onNodeWithText(context.getString(R.string.tv_loading)).assertIsFocused()
    }

    @Test fun anEmptyLibrary_saysSo() {
        show(DiscoverUiState())
        rule.onNodeWithText(context.getString(R.string.tv_home_empty)).assertIsFocused()
    }

    @Test fun whileTheFirstSyncRuns_itLoads() {
        show(DiscoverUiState(), syncing = true)
        rule.onNodeWithText(context.getString(R.string.tv_home_loading)).assertExists()
    }

    /** Review focus 2: no server, nothing stored, and a way to try again. */
    @Test fun offlineWithoutData_saysSo_andRetries() {
        show(DiscoverUiState(), online = false)
        rule.onNodeWithText(context.getString(R.string.tv_home_offline)).assertExists()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, retries)
    }

    @Test fun offlineWithData_showsTheGrid() {
        show(full, online = false)
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
    }

    /** Review focus 1: the focused card goes; the focus lands on the card now in its place. */
    @Test fun theFocusedCardGoes_theFocusMovesToTheCardInItsPlace() {
        var state by mutableStateOf(full)
        rule.setContent { TvTheme { TvLibraryScreen(state, false, {}, {}, {}, {}) } }
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("t2")).assertIsFocused()
        state = full.copy(all = titles - titles[1])
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("t3")).assertIsFocused()
    }

    /** Review focus 1: the grid empties while it has the focus; the message takes it. */
    @Test fun theGridEmpties_theMessageTakesTheFocus() {
        var state by mutableStateOf(full)
        rule.setContent { TvTheme { TvLibraryScreen(state, false, {}, {}, {}, {}) } }
        rule.onNodeWithTag(cardTag("t1")).assertIsFocused()
        state = DiscoverUiState()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
    }

}
