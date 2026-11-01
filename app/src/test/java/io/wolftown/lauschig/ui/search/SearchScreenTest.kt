package io.wolftown.lauschig.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(
        state: SearchUiState,
        onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
        onRecent: (String) -> Unit = {},
        onOpenItem: (String) -> Unit = {},
    ) = rule.setContent {
        LauschigTheme { SearchScreen(state, {}, {}, {}, onRecent, onOpenItem, onOpenBrowse) }
    }

    @Test fun searchScreen_tapAuthor_opensBrowse() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        show(
            SearchUiState(
                query = "ann", searched = true,
                titles = listOf(ItemSummary("s", "1", "Anna Karenina", "Writer", null, null, null, 0f, false, false)),
                authors = listOf(Facet("Anne Writer", 3, "1")),
                narrators = listOf(Facet("Anna Sprecher", 1, "1")),
            ),
            onOpenBrowse = { k, n -> opened += k to n },
        )
        rule.onNodeWithText("Titles").assertIsDisplayed()
        rule.onNodeWithText("Authors").assertIsDisplayed()
        rule.onNodeWithText("Narrators").assertIsDisplayed()
        rule.onNodeWithText("Anne Writer", substring = true).performClick()
        assertEquals(listOf(BrowseKind.AUTHOR to "Anne Writer"), opened)
    }

    @Test fun searchScreen_noMatch_showsHint() {
        show(SearchUiState(query = "zzz", resultsFor = "zzz", searched = true))
        rule.onNodeWithText("Nothing found for “zzz”").assertIsDisplayed()
    }

    @Test fun searchScreen_emptyField_showsRecentChips() {
        val picked = mutableListOf<String>()
        show(SearchUiState(recent = listOf("silber", "tolstoi")), onRecent = { picked += it })
        rule.onNodeWithText("tolstoi").performClick()
        assertEquals(listOf("tolstoi"), picked)
    }
}
