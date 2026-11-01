package io.wolftown.lauschig.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.ItemSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val both = setOf(LibraryKind.AUDIOBOOK, LibraryKind.HOERSPIEL)
    private fun book(id: String) = ItemSummary("s", id, "Titel $id", null, null, null, null, 0f, false, false)

    @Test fun showsTabsAndChips_tapOpensDetail_evenWhenNotDownloaded() {
        val opened = mutableListOf<String>()
        val kinds = mutableListOf<Set<LibraryKind>>()
        val tabs = mutableListOf<LibraryTab>()
        val state = LibraryUiState(availableKinds = both, items = listOf(book("a")), loaded = true)
        rule.setContent { LauschigTheme { LibraryScreen(state, { tabs += it }, { kinds += it }, { opened += it }) } }
        listOf("In progress", "Finished", "Downloaded", "Series", "All", "Audiobooks", "Radio plays").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNodeWithText("Radio plays").performClick()
        rule.onNodeWithText("Series").performClick()
        rule.onNodeWithText("Titel a").performClick()
        assertEquals(listOf(setOf(LibraryKind.HOERSPIEL)), kinds)
        assertEquals(listOf(LibraryTab.Series), tabs)
        assertEquals(listOf("a"), opened)
    }

    @Test fun singleKind_hidesChips_andEmptyShowsHint() {
        val state = LibraryUiState(availableKinds = setOf(LibraryKind.AUDIOBOOK), loaded = true)
        rule.setContent { LauschigTheme { LibraryScreen(state, {}, {}, {}) } }
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        rule.onNodeWithText("Audiobooks").assertDoesNotExist()
    }
}
