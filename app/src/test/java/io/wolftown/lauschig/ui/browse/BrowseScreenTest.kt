package io.wolftown.lauschig.ui.browse

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.absapi.library.AuthorDto
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
class BrowseScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun tile(id: String, title: String) = BrowseEntry.Tile(ItemSummary("s", id, title, "Tile Writer", null, null, null, 0f, false, false))

    private fun show(state: BrowseUiState, onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> }, onOpenItem: (String) -> Unit = {}) =
        rule.setContent { LauschigTheme { BrowseScreen(state, {}, {}, onOpenItem, onOpenBrowse) } }

    @Test fun authorPage_offline_showsBooksWithoutBio() {
        val opened = mutableListOf<String>()
        show(
            BrowseUiState(
                BrowseKind.AUTHOR, "Ann", entries = listOf(tile("1", "Book One"), tile("2", "Book Two")), count = 2,
                series = listOf(Facet("Saga", 2, "1")), loaded = true,
            ),
            onOpenItem = { opened += it },
        )
        rule.onNodeWithText("Ann").assertIsDisplayed()
        rule.onNodeWithText("2 titles").assertIsDisplayed()
        rule.onNodeWithText("Book One").assertIsDisplayed().performClick()
        rule.onNodeWithText("Book Two").assertIsDisplayed()
        rule.onNodeWithText("Series").assertIsDisplayed()
        rule.onAllNodesWithTag("authorImage").assertCountEquals(0)
        assertEquals(listOf("1"), opened)
    }

    @Test fun authorPage_seriesChip_opensSeries() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        show(
            BrowseUiState(BrowseKind.AUTHOR, "Ann", entries = listOf(tile("1", "Book One")), count = 1, series = listOf(Facet("Saga", 1, "1")), loaded = true),
            onOpenBrowse = { k, n -> opened += k to n },
        )
        rule.onNodeWithText("Saga").performClick()
        assertEquals(listOf(BrowseKind.SERIES to "Saga"), opened)
    }

    @Test fun authorPage_bio_isShownWhenLoaded() {
        show(BrowseUiState(BrowseKind.AUTHOR, "Ann", author = AuthorDto("a1", "Ann", "Wrote many books."), loaded = true))
        rule.onNodeWithText("Wrote many books.").assertIsDisplayed()
    }

    @Test fun seriesPage_showsGapPlaceholder_notClickable() {
        show(
            BrowseUiState(
                BrowseKind.SERIES, "Saga", entries = listOf(tile("1", "Vol 1"), BrowseEntry.Missing("2"), tile("3", "Vol 3")), count = 2, loaded = true,
            ),
        )
        rule.onNodeWithText("Volume 2").assertIsDisplayed()
        rule.onNodeWithText("not in the library").assertIsDisplayed()
        rule.onNodeWithText("Volume 2").assert(!hasClickAction())
        rule.onNodeWithText("2 titles").assertIsDisplayed()
    }

    @Test fun emptyPage_showsHint() {
        show(BrowseUiState(BrowseKind.GENRE, "Horror", loaded = true))
        rule.onNodeWithText("Nothing here (in the selected libraries)").assertIsDisplayed()
    }
}
