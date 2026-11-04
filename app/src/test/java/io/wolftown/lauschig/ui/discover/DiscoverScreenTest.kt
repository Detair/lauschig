package io.wolftown.lauschig.ui.discover

import io.wolftown.lauschig.db.entities.LibraryKind
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.nav.BrowseKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class DiscoverScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun summary(id: String, title: String) = ItemSummary("s", id, title, "Writer", null, null, null, 0f, false, false)

    private val state = DiscoverUiState(
        recent = listOf(summary("1", "Frisch")),
        series = listOf(SeriesTile("Die Reihe", 3, summary("1", "Band 1"))),
        genres = listOf(Facet("Krimi", 4, "1")),
        tags = listOf(Facet("Gute Nacht", 2, "1")),
        authors = listOf(AuthorTile("Ann Writer", 2, null)),
        all = listOf(summary("1", "Frisch"), summary("2", "Alt")),
    )

    private fun show(
        onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
        onSort: (DiscoverSort) -> Unit = {},
        onOpenItem: (String) -> Unit = {},
    ) = rule.setContent {
        LauschigTheme { DiscoverScreen(state, {}, onSort, onOpenItem, onOpenBrowse) }
    }

    @Test fun discoverScreen_tapSeries_opensSeriesPage() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        show(onOpenBrowse = { k, n -> opened += k to n })
        rule.onNodeWithText("3 volumes").assertIsDisplayed()
        rule.onNodeWithText("Die Reihe").performClick()
        rule.onNodeWithText("Krimi").performClick()
        rule.onNodeWithText("Gute Nacht").performScrollTo().performClick()
        rule.onNodeWithText("Ann Writer", substring = true).performScrollTo().performClick()
        assertEquals(
            listOf(BrowseKind.SERIES to "Die Reihe", BrowseKind.GENRE to "Krimi", BrowseKind.TAG to "Gute Nacht", BrowseKind.AUTHOR to "Ann Writer"),
            opened,
        )
    }

    @Test fun discoverScreen_sortMenu_picksSort() {
        val picked = mutableListOf<DiscoverSort>()
        show(onSort = { picked += it })
        rule.onNodeWithText("Sort: Title").performClick()
        rule.onNodeWithText("Duration").performClick()
        assertEquals(listOf(DiscoverSort.DURATION), picked)
    }

    @Test fun discoverScreen_emptyCatalog_showsHint_andRefreshes() {
        var refreshed = 0
        rule.setContent { LauschigTheme { DiscoverScreen(DiscoverUiState(), {}, {}, {}, { _, _ -> }, onRefresh = { refreshed++ }) } }
        rule.onNodeWithText("Nothing here yet", substring = true).assertIsDisplayed()
        rule.onNodeWithText("The library loads the next time your server is reachable.").assertIsDisplayed()
        rule.onNodeWithText("Refresh library").performClick()
        assertEquals(1, refreshed)
    }

    @Test fun discoverScreen_refreshRunning_showsIt_andCannotBeTappedAgain() {
        var refreshed = 0
        rule.setContent { LauschigTheme { DiscoverScreen(DiscoverUiState(), {}, {}, {}, { _, _ -> }, onRefresh = { refreshed++ }, refreshing = true) } }
        rule.onNodeWithText("Refreshing library…").assertIsDisplayed()
        rule.onNodeWithTag("refreshLibrary").assertIsNotEnabled().performClick()
        assertEquals(0, refreshed)
    }

    @Test fun discoverScreen_emptyOnlyBecauseOfTheFilter_saysSo_andResetsIt() {
        val kinds = mutableListOf<Set<LibraryKind>>()
        val state = DiscoverUiState(availableKinds = setOf(LibraryKind.AUDIOBOOK, LibraryKind.HOERSPIEL), selectedKinds = setOf(LibraryKind.HOERSPIEL), hiddenByFilter = true)
        rule.setContent { LauschigTheme { DiscoverScreen(state, { kinds += it }, {}, {}, { _, _ -> }) } }
        rule.onNodeWithText("No titles with this filter").assertIsDisplayed()
        rule.onNodeWithText("Refresh library").assertDoesNotExist()
        rule.onNodeWithText("Reset filter").performClick()
        assertEquals(listOf(emptySet<LibraryKind>()), kinds)
    }

    @Test fun discoverScreen_emptyCatalogWithAFilterSet_offersTheRefresh_notTheReset() {
        val state = DiscoverUiState(availableKinds = setOf(LibraryKind.AUDIOBOOK, LibraryKind.HOERSPIEL), selectedKinds = setOf(LibraryKind.HOERSPIEL))
        rule.setContent { LauschigTheme { DiscoverScreen(state, {}, {}, {}, { _, _ -> }) } }
        rule.onNodeWithText("Refresh library").assertIsDisplayed()
        rule.onNodeWithText("No titles with this filter").assertDoesNotExist()
    }

    @Test fun discoverScreen_whileLoading_saysNothingIsThere_never() {
        rule.setContent { LauschigTheme { DiscoverScreen(DiscoverUiState(loading = true), {}, {}, {}, { _, _ -> }) } }
        rule.onNodeWithTag("discoverEmpty").assertDoesNotExist()
        rule.onNodeWithText("Nothing here yet", substring = true).assertDoesNotExist()
        // A quiet indicator, once loading takes a moment.
        rule.onNodeWithTag("loadingState").assertExists()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithContentDescription("Loading…").assertExists()
    }
}
