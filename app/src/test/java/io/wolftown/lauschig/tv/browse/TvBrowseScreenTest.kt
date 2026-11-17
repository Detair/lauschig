package io.wolftown.lauschig.tv.browse

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
import io.wolftown.lauschig.tv.common.TAG_MESSAGE
import io.wolftown.lauschig.tv.common.TAG_MESSAGE_ACTION
import io.wolftown.lauschig.tv.common.cardTag
import io.wolftown.lauschig.tv.common.tvItem
import io.wolftown.lauschig.ui.browse.BrowseEntry
import io.wolftown.lauschig.ui.browse.BrowseUiState
import io.wolftown.lauschig.ui.nav.BrowseKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvBrowseScreenTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val series = BrowseUiState(
        kind = BrowseKind.SERIES, name = "Die drei", loaded = true, count = 3,
        entries = listOf(
            BrowseEntry.Tile(tvItem("v1")),
            BrowseEntry.Missing("2"),
            BrowseEntry.Tile(tvItem("v3")),
            BrowseEntry.Tile(tvItem("v4")),
        ),
        askMissingVolumes = true,
    )

    private val opened = mutableListOf<String>()
    private var retries = 0

    private fun show(state: BrowseUiState, online: Boolean = true) = rule.setContent {
        TvTheme { TvBrowseScreen(state, onOpenItem = { opened += it }, online = online, onRetry = { retries++ }) }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun nameAndCount_andTheFocusOnTheFirstVolume() {
        show(series)
        rule.onNodeWithTag(TAG_BROWSE_TITLE).assertExists()
        rule.onNodeWithText("Die drei").assertExists()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.browse_title_count, 3, 3)).assertExists()
        rule.onNodeWithTag(cardTag("v1")).assertIsFocused()
    }

    /** The order of the entries; the gap is left out, and nothing asks about it. */
    @Test fun theVolumesInOrder_withoutTheGap_andOkOpens() {
        show(series)
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("v3")).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(cardTag("v4")).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("v4"), opened)
        rule.onNodeWithText(context.getString(R.string.browse_missing_volume, "2")).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.browse_ask_missing_title)).assertDoesNotExist()
    }

    @Test fun loading_saysSo() {
        show(BrowseUiState(loaded = false))
        rule.onNodeWithText(context.getString(R.string.tv_loading)).assertIsFocused()
    }

    @Test fun empty_saysSo() {
        show(BrowseUiState(loaded = true, entries = listOf(BrowseEntry.Missing("1"))))
        rule.onNodeWithText(context.getString(R.string.tv_browse_empty)).assertIsFocused()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertDoesNotExist()
    }

    /** Review focus 2. */
    @Test fun offlineAndEmpty_saysSo_andRetries() {
        show(BrowseUiState(loaded = true), online = false)
        rule.onNodeWithText(context.getString(R.string.tv_browse_offline)).assertExists()
        rule.onNodeWithTag(TAG_MESSAGE_ACTION).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, retries)
    }

    @Test fun offlineWithTitles_showsThem() {
        show(series, online = false)
        rule.onNodeWithTag(cardTag("v1")).assertIsFocused()
    }

    /** Review focus 1: the focused volume goes; the focus moves to the one now in its place. */
    @Test fun theFocusedVolumeGoes_theFocusStaysInTheGrid() {
        var state by mutableStateOf(series)
        rule.setContent { TvTheme { TvBrowseScreen(state, {}) } }
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(cardTag("v4")).assertIsFocused()
        state = series.copy(entries = series.entries.dropLast(1), count = 2)
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("v3")).assertIsFocused()
        state = series.copy(entries = emptyList(), count = 0)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MESSAGE).assertIsFocused()
    }

    /** As after Back from a title: the page comes again, and the focus is on the volume it was on. */
    @Test fun comingBackFromATitle_landsOnTheSameVolume() {
        var shown by mutableStateOf(true)
        rule.setContent {
            val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            TvTheme { if (shown) pages.SaveableStateProvider("browse") { TvBrowseScreen(series, { opened += it }) } }
        }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("v3"), opened)
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithTag(cardTag("v3")).assertIsFocused()
    }
}
