package io.wolftown.lauschig.ui.detail

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
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

    private fun show(state: ItemDetailUiState, onDelete: () -> Unit = {}, onChapter: (Int) -> Unit = {}, onPrimary: () -> Unit = {}, settledOffline: Boolean = false) =
        rule.setContent {
            CompositionLocalProvider(LocalOfflineDimming provides settledOffline) {
                LauschigTheme { ItemDetailScreen(state, {}, onPrimary, {}, {}, onDelete, onChapter) }
            }
        }

    @Test fun detailChips_openBrowse_perPerson() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        rule.setContent {
            LauschigTheme {
                ItemDetailScreen(
                    base.copy(author = "Lucy Hawking, Stephen Hawking", narrator = "Nick Narrator"), {}, {}, {}, {}, {}, {},
                    onOpenBrowse = { k, n -> opened += k to n },
                )
            }
        }
        for (t in listOf("Lucy Hawking", "Stephen Hawking", "Nick Narrator", "Saga · Book 3", "Fantasy")) {
            rule.onNodeWithText(t).assertIsDisplayed().performClick()
        }
        assertEquals(
            listOf(
                BrowseKind.AUTHOR to "Lucy Hawking", BrowseKind.AUTHOR to "Stephen Hawking", BrowseKind.NARRATOR to "Nick Narrator",
                BrowseKind.SERIES to "Saga", BrowseKind.GENRE to "Fantasy",
            ),
            opened,
        )
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
        show(base.copy(online = false, detailsLoaded = false, chapters = emptyList()), settledOffline = true)
        rule.onNodeWithText("Chapters available once online").assertIsDisplayed()
        rule.onNodeWithText("Play").assertIsNotEnabled()
        rule.onNodeWithText("Not downloaded – available when online").assertIsDisplayed()
        rule.onNodeWithText("Available when online").assertIsDisplayed()
        rule.onNodeWithContentDescription("Download").assertIsNotEnabled()
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

    @Test fun notSettledOffline_noDisabledReason_noHint() {
        show(base.copy(online = false, detailsLoaded = false, chapters = emptyList()), settledOffline = false)
        rule.onNodeWithText("Play").assertIsEnabled()
        rule.onNodeWithContentDescription("Download").assertIsEnabled()
        rule.onNodeWithText("Not downloaded – available when online").assertDoesNotExist()
        rule.onNodeWithText("Chapters available once online").assertDoesNotExist()
    }

    @Test fun offline_pausedAndFailed_startDisabled_removeEnabled() {
        show(base.copy(download = DownloadUi.Paused(false)), settledOffline = true)
        rule.onNodeWithContentDescription("Resume").assertIsNotEnabled()
        rule.onNodeWithText("Available when online").assertIsDisplayed()
        rule.onNodeWithText("Cancel download").assertIsEnabled()
    }

    @Test fun offline_failed_retryDisabled_removeEnabled() {
        show(base.copy(download = DownloadUi.Failed(R.string.download_error_generic)), settledOffline = true)
        rule.onNodeWithContentDescription("Retry").assertIsNotEnabled()
        rule.onNodeWithText("Remove").assertIsEnabled()
        rule.onNodeWithText("Available when online").assertIsDisplayed()
    }

    @Test fun cancelWithPartialData_asksForConfirmation() {
        var deleted = 0
        show(base.copy(download = DownloadUi.Paused(true)), onDelete = { deleted++ })
        rule.onNodeWithText("Cancel download").performClick()
        assertEquals(0, deleted)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test fun failedDownload_showsMappedError() {
        show(base.copy(download = DownloadUi.Failed(R.string.download_error_not_found)))
        rule.onNodeWithText("Not found on server").assertIsDisplayed()
        rule.onNodeWithContentDescription("Retry").assertIsDisplayed()
    }

    @Test fun longDescription_collapsedWithMore() {
        show(base.copy(description = (1..80).joinToString("\n") { "Line $it" }))
        rule.onNodeWithText("More").assertIsDisplayed().performClick()
        rule.onNodeWithText("Less").assertIsDisplayed()
        rule.onNodeWithText("Line 80").assertDoesNotExist()
        rule.onNodeWithText("Line 1", substring = true).assertExists()
    }

    @Test fun formatClock_hoursOnlyWhenNeeded() {
        assertEquals("44:15", formatClock(2655.0))
        assertEquals("1:02:03", formatClock(3723.0))
    }

    @Test fun detailGenres_blankEntriesGetNoChip() {
        val genres = androidx.compose.runtime.mutableStateOf(listOf("Fantasy"))
        rule.setContent { LauschigTheme { ItemDetailScreen(base.copy(genres = genres.value), {}, {}, {}, {}, {}, {}) } }
        val clickable = { rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size }
        val before = clickable()
        genres.value = listOf("Fantasy", " ", "", "  Fantasy ")
        rule.waitForIdle()
        assertEquals(before, clickable())
    }

    @Test fun quickBar_lengthNarratorYear_underTheTitle_narratorOpensItsPage() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        rule.setContent {
            LauschigTheme {
                ItemDetailScreen(
                    base.copy(durationSec = 14 * 3600.0 + 22 * 60, publishedYear = "2024"), {}, {}, {}, {}, {}, {},
                    onOpenBrowse = { k, n -> opened += k to n },
                )
            }
        }
        rule.onNodeWithTag("quickBar").assertIsDisplayed()
        rule.onNodeWithText("14 h 22 min").assertContentDescriptionEquals("Length: 14 h 22 min")
        rule.onNodeWithText("2024").assertContentDescriptionEquals("Published: 2024")
        // The narrator moved from the chips into the bar (with its microphone), once, and still opens the narrator's page.
        rule.onAllNodesWithText("Nick Narrator").assertCountEquals(1)
        rule.onNodeWithText("Nick Narrator").assertContentDescriptionEquals("Read by Nick Narrator").performClick()
        assertEquals(listOf(BrowseKind.NARRATOR to "Nick Narrator"), opened)
        // Under the title, above the chips and the buttons.
        val title = rule.onNodeWithText("The Book").getUnclippedBoundsInRoot()
        val bar = rule.onNodeWithTag("quickBar").getUnclippedBoundsInRoot()
        val chip = rule.onNodeWithText("Ann Author").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithTag("primaryButton").getUnclippedBoundsInRoot()
        assertTrue(bar.top >= title.bottom && bar.bottom <= chip.top && bar.bottom <= primary.top)
    }

    @Test fun quickBar_severalNarrators_oneFact_aMenuPicksWhose() {
        val opened = mutableListOf<Pair<BrowseKind, String>>()
        rule.setContent {
            LauschigTheme { ItemDetailScreen(base.copy(narrator = "Oliver Rohrbeck, Jens Wawrczeck"), {}, {}, {}, {}, {}, {}, onOpenBrowse = { k, n -> opened += k to n }) }
        }
        rule.onNodeWithText("Oliver Rohrbeck, Jens Wawrczeck").assertContentDescriptionEquals("Read by Oliver Rohrbeck, Jens Wawrczeck").performClick()
        rule.onNodeWithText("Jens Wawrczeck").performClick()
        assertEquals(listOf(BrowseKind.NARRATOR to "Jens Wawrczeck"), opened)
    }

    @Test fun quickBar_leavesOutWhatIsUnknown() {
        show(base.copy(narrator = null, durationSec = 0.0, publishedYear = null))
        rule.onNodeWithTag("quickBar").assertDoesNotExist()
    }

    @Test fun buttons_largePrimary_withACompactDownloadBesideIt() {
        val calls = mutableListOf<String>()
        rule.setContent { LauschigTheme { ItemDetailScreen(base.copy(primary = PrimaryAction.Continue(2)), {}, { calls += "play" }, { calls += "download" }, {}, {}, {}) } }
        val primary = rule.onNodeWithTag("primaryButton").assertHeightIsAtLeast(56.dp)
        val download = rule.onNodeWithTag("downloadCompanion").assertContentDescriptionEquals("Download").assertWidthIsAtLeast(48.dp)
        val p = primary.getUnclippedBoundsInRoot()
        val d = download.getUnclippedBoundsInRoot()
        assertTrue("download not beside the primary button", d.left >= p.right && d.top < p.bottom && d.bottom > p.top)
        assertTrue("primary not the larger one", p.width > d.width * 2)
        primary.performClick()
        download.performClick()
        assertEquals(listOf("play", "download"), calls)
    }

    @Test fun downloadCompanion_followsTheState() {
        var state by androidx.compose.runtime.mutableStateOf(base.copy(download = DownloadUi.Running(42, true)))
        val calls = mutableListOf<String>()
        rule.setContent { LauschigTheme { ItemDetailScreen(state, {}, {}, { calls += "download" }, { calls += "pause" }, { calls += "delete" }, {}) } }
        // Running: the ring shows the progress, a tap pauses (the line below still says it in words).
        rule.onNodeWithTag("downloadCompanion").assertContentDescriptionEquals("Downloading 42 %").performClick()
        rule.onNodeWithText("Downloading 42 %").assertIsDisplayed()
        state = base.copy(download = DownloadUi.Complete(812_000_000))
        rule.onNodeWithText("Downloaded (812\u00A0MB)").assertIsDisplayed() // number and unit stay together
        rule.onNodeWithTag("downloadCompanion").assertContentDescriptionEquals("Available on this device").performClick()
        rule.onNodeWithText("Delete download?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(listOf("pause", "delete"), calls)
    }

    @Test fun deviceTitle_noDownloadCompanion() {
        show(base.copy(isLocal = true))
        rule.onNodeWithTag("downloadCompanion").assertDoesNotExist()
        rule.onNodeWithTag("localSource").assertExists()
    }

    @Test @Config(sdk = [35], qualifiers = "de-w400dp-h2400dp")
    fun quickBar_german() {
        show(base.copy(durationSec = 14 * 3600.0 + 22 * 60, publishedYear = "2024"))
        rule.onNodeWithText("14 Std. 22 Min.").assertContentDescriptionEquals("Länge: 14 Std. 22 Min.")
        rule.onNodeWithText("Nick Narrator").assertContentDescriptionEquals("Gelesen von Nick Narrator")
        rule.onNodeWithText("2024").assertContentDescriptionEquals("Erschienen: 2024")
    }
}
