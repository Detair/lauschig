package io.wolftown.lauschig.ui.detail

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp")
class ItemDetailScreenTest {
    @get:Rule val rule = createComposeRule()

    private val chapters = listOf(
        ChapterRow(0, "One", 100.0, false),
        ChapterRow(1, "Two", 100.0, true, 30.0),
    )
    private val base = ItemDetailUiState(
        found = true, itemId = "b1", title = "The Book", author = "Ann Author", narrator = "Nick Narrator",
        seriesName = "Saga", seriesSequence = "3", genres = listOf("Fantasy"), detailsLoaded = true, online = true, chapters = chapters,
    )

    private fun show(state: ItemDetailUiState, onDelete: () -> Unit = {}, onChapter: (Int) -> Unit = {}, onPrimary: () -> Unit = {}) =
        rule.setContent { LauschigTheme { ItemDetailScreen(state, {}, onPrimary, {}, {}, onDelete, onChapter) } }

    @Test fun chips_showAllAndAreNotClickable() {
        show(base)
        for (t in listOf("Ann Author", "Nick Narrator", "Saga · Book 3", "Fantasy")) {
            rule.onNodeWithText(t).assertIsDisplayed().assertHasNoClickAction()
        }
    }

    @Test fun primary_texts() {
        show(base.copy(primary = PrimaryAction.Continue(2)))
        rule.onNodeWithText("Continue · Ch. 2").assertIsDisplayed()
    }

    @Test fun currentChapter_showsPositionAndTap() {
        val taps = mutableListOf<Int>()
        show(base, onChapter = { taps += it })
        rule.onNodeWithTag("currentChapter").assertIsDisplayed()
        rule.onNodeWithText("00:30 / 01:40").assertIsDisplayed()
        rule.onNodeWithText("One").performClick()
        assertEquals(listOf(0), taps)
    }

    @Test fun offlineNoDetails_showsHint_andDisabledPlayWithReason() {
        show(base.copy(online = false, detailsLoaded = false, chapters = emptyList(), playEnabled = false,
            playDisabledReason = R.string.error_not_downloaded_offline, downloadEnabled = false))
        rule.onNodeWithText("Chapters available once online").assertIsDisplayed()
        rule.onNodeWithText("Play").assertIsNotEnabled()
        rule.onNodeWithText("Not downloaded – available when online").assertIsDisplayed()
        rule.onNodeWithText("Available when online").assertIsDisplayed()
        rule.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test fun deleteDownload_asksForConfirmation() {
        var deleted = 0
        show(base.copy(download = DownloadUi.Complete(812_000_000)), onDelete = { deleted++ })
        rule.onNodeWithText("Delete download").performClick()
        assertEquals(0, deleted)
        rule.onNodeWithText("Delete download?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test fun failedDownload_showsMappedError() {
        show(base.copy(download = DownloadUi.Failed(R.string.download_error_not_found)))
        rule.onNodeWithText("Not found on server").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun longDescription_collapsedWithMore() {
        show(base.copy(description = (1..80).joinToString("\n") { "Line $it" }))
        rule.onNodeWithText("More").assertIsDisplayed().performClick()
        rule.onNodeWithText("Less").assertIsDisplayed()
        assertTrue(true)
    }

    @Test fun formatClock_hoursOnlyWhenNeeded() {
        assertEquals("44:15", formatClock(2655.0))
        assertEquals("1:02:03", formatClock(3723.0))
    }
}
