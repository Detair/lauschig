package io.wolftown.lauschig.ui.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.DownloadEntryRow
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.download.DownloadSort
import io.wolftown.lauschig.download.DownloadWorker
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
/** A tall screen so every row of the list is composed. */
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2000dp")
class DownloadsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun entry(key: String, status: EntryStatus, finished: Boolean = false) =
        DownloadEntryUi(key, "Titel $key", "Autor", status, bytesOnDisk = 1_000_000, bytesTotal = 2_000_000, finished = finished)

    private val state = DownloadsUiState(
        loaded = true,
        entries = listOf(
            entry("run", EntryStatus.Running(50)),
            entry("wait", EntryStatus.Queued(waitingForNetwork = true, retrying = false)),
            entry("stream", EntryStatus.Paused(forStream = true)),
            entry("bad", EntryStatus.Failed(R.string.download_error_storage_full)),
            entry("done", EntryStatus.Complete, finished = true),
        ),
        total = 5, usedBytes = 5_000_000, freeBytes = 10_000_000_000, finishedCount = 1, finishedBytes = 1_000_000,
    )

    @Test fun showsStorage_statesAndReasons() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertIsDisplayed()
        rule.onNodeWithText("Waiting for a connection").assertIsDisplayed()
        rule.onNodeWithText("Paused so the stream keeps playing", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Not enough storage").assertIsDisplayed()
        rule.onNodeWithText("Downloaded", substring = true).assertIsDisplayed()
    }

    @Test fun actionsPerState_sortAndOpen() {
        val calls = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val actions = DownloadsActions(
            onSort = { calls += "sort $it" }, onQuery = { calls += "query $it" }, onPause = { calls += "pause $it" },
            onResume = { calls += "resume $it" }, onRemove = { calls += "remove $it" }, onDeleteFinished = { calls += "finished" },
        )
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, { opened += it }, actions) } }
        rule.onNodeWithTag("pause:run").performClick()
        rule.onNodeWithTag("pause:wait").performClick()
        rule.onNodeWithTag("resume:stream").performClick()
        rule.onNodeWithTag("retry:bad").performClick()
        rule.onNodeWithTag("cancel:run").performClick()
        rule.onNodeWithTag("sort:${DownloadSort.SIZE}").performClick()
        rule.onNodeWithText("Titel done").performClick()
        assertEquals(listOf("pause run", "pause wait", "resume stream", "resume bad", "remove run", "sort SIZE"), calls)
        assertEquals(listOf("done"), opened)
    }

    @Test fun deletingAFinishedDownload_andDeleteFinished_askFirst() {
        val calls = mutableListOf<String>()
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions(onRemove = { calls += "remove $it" }, onDeleteFinished = { calls += "finished" })) } }
        rule.onNodeWithTag("delete:done").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("confirmDelete").performClick()
        rule.onNodeWithTag("deleteFinished").performClick()
        assertEquals(listOf("remove done"), calls)
        rule.onNodeWithTag("confirmDeleteFinished").performClick()
        assertEquals(listOf("remove done", "finished"), calls)
    }

    @Test fun empty() {
        rule.setContent { LauschigTheme { DownloadsScreen(DownloadsUiState(loaded = true), {}, {}, DownloadsActions()) } }
        rule.onNodeWithText("No downloads yet").assertIsDisplayed()
        rule.onNodeWithText("Downloaded titles play without a connection.").assertIsDisplayed()
    }

    @Test fun notLoadedYet_showsNoEmptyHint() {
        rule.setContent { LauschigTheme { DownloadsScreen(DownloadsUiState(loaded = false), {}, {}, DownloadsActions()) } }
        rule.onNodeWithText("No downloads yet").assertDoesNotExist()
        rule.onNodeWithTag("loadingState").assertExists()
    }

    @Test fun noMatch_keepsTheSearchField() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(entries = emptyList(), query = "xyz"), {}, {}, DownloadsActions()) } }
        rule.onNodeWithText("No download matches the search").assertIsDisplayed()
        rule.onNodeWithTag("downloadsSearch").assertIsDisplayed()
    }

    @Test fun entryStatus_mapsTheWorkerCodes() {
        fun row(state: DownloadState, error: String? = null, done: Long = 0, total: Long = 0) =
            DownloadEntryRow("k", state, error, 0, "t", null, done, total, null, false)
        assertEquals(EntryStatus.Queued(waitingForNetwork = true, retrying = false), entryStatus(row(DownloadState.QUEUED, "offline")))
        assertEquals(EntryStatus.Queued(waitingForNetwork = false, retrying = true), entryStatus(row(DownloadState.QUEUED, "http_500")))
        assertEquals(EntryStatus.Queued(waitingForNetwork = false, retrying = false), entryStatus(row(DownloadState.QUEUED)))
        assertEquals(EntryStatus.Running(25), entryStatus(row(DownloadState.RUNNING, done = 25, total = 100)))
        assertEquals(EntryStatus.Paused(forStream = true), entryStatus(row(DownloadState.PAUSED, DownloadWorker.STREAM_PRIORITY)))
        assertEquals(EntryStatus.Paused(forStream = false), entryStatus(row(DownloadState.PAUSED)))
        assertEquals(EntryStatus.Failed(R.string.download_error_not_found), entryStatus(row(DownloadState.FAILED, "http_404")))
        assertEquals(EntryStatus.Failed(R.string.download_error_generic), entryStatus(row(DownloadState.FAILED, "invalid_path")))
        assertEquals(EntryStatus.Complete, entryStatus(row(DownloadState.COMPLETE)))
    }
    @Test fun finishedTitles_areCalledHeard_likeTheGermanGehoert() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithText("Delete heard", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Downloaded", substring = true).assertTextContains("Heard", substring = true)
    }

    // The literal texts, with the sizes as Robolectric's Formatter writes them.
    @Test fun header_withoutKeptData_isUnchanged() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5.0\u00A0MB used · 10\u00A0GB free")
    }

    @Test fun header_namesTheDownloadsOfEarlierAccounts() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(usedBytes = 800_000_000, keptBytes = 600_000_000), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("800\u00A0MB used (600\u00A0MB from earlier accounts) · 10\u00A0GB free")
    }

    @Test fun header_withoutFreeSpace_stillNamesThem() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(freeBytes = null, keptBytes = 2_000_000), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5.0\u00A0MB used (2.0\u00A0MB from earlier accounts)")
    }

    @Test fun header_aLineForTheOtherProfiles() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(otherProfilesBytes = 2_100_000_000), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsOtherProfiles").assertTextEquals("Other profiles on this device: 2.1\u00A0GB")
        // Their figure is not in the account's own, and nothing here deletes them.
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5.0\u00A0MB used · 10\u00A0GB free")
    }

    @Test fun header_withoutOtherProfiles_noSuchLine() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsOtherProfiles").assertDoesNotExist()
    }

    @Test fun aMissingCard_isSaidUnderTheStorageFigure() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(cardMissing = true), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsCardMissing").assertTextEquals("SD card not found – new downloads go to the internal storage for now.")
    }

    @Test fun noCardHint_whileTheCardIsThere() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsCardMissing").assertDoesNotExist()
    }

    @Test fun header_withoutFreeSpaceAndKeptData_isJustTheUsedFigure() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(freeBytes = null), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5.0\u00A0MB used")
    }

    @Config(qualifiers = "+de")
    @Test fun header_inGerman_namesTheDownloadsOfEarlierAccounts() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(usedBytes = 800_000_000, keptBytes = 600_000_000), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("800\u00A0MB belegt (davon 600\u00A0MB von früheren Konten) · 10\u00A0GB frei")
    }

    @Config(qualifiers = "+de")
    @Test fun header_inGerman_withoutKeptData_isUnchanged() {
        rule.setContent { LauschigTheme { DownloadsScreen(state, {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5,0\u00A0MB belegt · 10\u00A0GB frei")
    }

    @Config(qualifiers = "+de")
    @Test fun header_inGerman_withoutFreeSpace() {
        rule.setContent { LauschigTheme { DownloadsScreen(state.copy(freeBytes = null, keptBytes = 2_000_000), {}, {}, DownloadsActions()) } }
        rule.onNodeWithTag("downloadsStorage").assertTextEquals("5,0\u00A0MB belegt (davon 2,0\u00A0MB von früheren Konten)")
    }

    @Test fun noBreakSpaces_joinANumberToItsUnit() {
        assertEquals("22\u00A0MB", "22 MB".noBreakSpaces())
        assertEquals("8,8\u00A0GB", "8,8\u00A0GB".noBreakSpaces())
    }
}
