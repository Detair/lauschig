package io.wolftown.lauschig.local

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.LocalFolderWithCount
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.history.HistoryRepository
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.detail.ItemDetailScreen
import io.wolftown.lauschig.ui.detail.ItemDetailUiState
import io.wolftown.lauschig.ui.detail.ItemDetailViewModel
import io.wolftown.lauschig.ui.extras.TitleExtrasViewModel
import io.wolftown.lauschig.ui.library.LibraryScreen
import io.wolftown.lauschig.ui.library.LibraryTab
import io.wolftown.lauschig.ui.library.LibraryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Player : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val plays = mutableListOf<String>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { plays += "$serverId/$itemId" }
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

private object NoDownloads : DownloadManager {
    override suspend fun enqueue(serverId: String, itemId: String, network: androidx.work.NetworkType?) = error("no downloads for local titles")
    override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) = error("no downloads for local titles")
    override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h2000dp")
class LocalUiTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val viewModels = TestViewModels()
    private val dev = DeviceLibrary.SERVER_ID
    private val local = DeviceLibrary.titleId("content://tree/x", "dir:a")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun tile(id: String, title: String) = ItemSummary(dev, id, title, "Ende", null, null, null, 0.3f, isDownloaded = true, isFinished = false)
    private fun folder(name: String, titles: Int = 1, error: String? = null) = LocalFolderWithCount("content://tree/$name", name, 1, 2, error, titles)

    @Test fun deviceTab_withoutFolders_offersToAddOne() {
        var added = 0
        rule.setContent { LauschigTheme { DeviceTabContent(LocalLibraryUiState(loaded = true), {}, {}, {}, onAddFolder = { added++ }, onRescan = {}) } }
        rule.onNodeWithTag("deviceEmpty").assertIsDisplayed()
        rule.onNodeWithTag("deviceAddFolder").performClick()
        assertEquals(1, added)
    }

    @Test fun deviceTab_listsTitles_folderErrors_andScanState() {
        val opened = mutableListOf<String>()
        val played = mutableListOf<String>()
        var rescans = 0
        val state = MutableStateFlow(
            LocalLibraryUiState(listOf(tile(local, "Momo")), listOf(folder("Hörbücher"), folder("SD-Karte", 0, LocalFolderEntity.ERROR_NO_ACCESS)), scanning = false, loaded = true),
        )
        rule.setContent {
            LauschigTheme { DeviceTabContent(state.collectAsStateValue(), { opened += it }, { played += it }, {}, onAddFolder = {}, onRescan = { rescans++ }) }
        }
        rule.onNodeWithText("Momo").performClick()
        rule.onNodeWithText("SD-Karte", substring = true).assertIsDisplayed()
        rule.onNodeWithText("No access any more", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("deviceRescan").performClick()
        assertEquals(listOf(local), opened)
        assertEquals(1, rescans)
        state.value = state.value.copy(scanning = true, titles = emptyList())
        rule.onNodeWithTag("deviceScanning").assertIsDisplayed()
        state.value = state.value.copy(scanning = false)
        rule.onNodeWithTag("deviceNoTitles").assertIsDisplayed()
    }

    @Test fun settingsSection_removeAsksFirst_addAndRescan() {
        val calls = mutableListOf<String>()
        val state = LocalLibraryUiState(folders = listOf(folder("Hörbücher", titles = 3)), loaded = true)
        rule.setContent { LauschigTheme { LocalFoldersSectionContent(state, onAdd = { calls += "add" }, onRemove = { calls += "remove $it" }, onRescan = { calls += "rescan" }) } }
        rule.onNodeWithText("3 titles").assertIsDisplayed()
        rule.onNodeWithTag("removeFolder:Hörbücher").performClick()
        assertEquals(emptyList<String>(), calls)
        rule.onNodeWithTag("confirmRemoveFolder").performClick()
        rule.onNodeWithTag("addFolder").performClick()
        rule.onNodeWithTag("rescanFolders").performClick()
        assertEquals(listOf("remove content://tree/Hörbücher", "add", "rescan"), calls)
    }

    @Test fun library_deviceTabShowsItsSlot_downloadedTabLinksToTheDownloadsPage() {
        val shown = mutableListOf<String>()
        var opened = 0
        val tab = MutableStateFlow(LibraryTab.Device)
        rule.setContent {
            LauschigTheme {
                LibraryScreen(LibraryUiState(tab = tab.collectAsStateValue(), loaded = true), {}, {}, {}, device = { shown += "device" }, onOpenDownloads = { opened++ })
            }
        }
        rule.onNodeWithText("On this device").assertIsDisplayed()
        assertTrue(shown.isNotEmpty())
        tab.value = LibraryTab.Downloaded
        rule.onNodeWithTag("openDownloads").performClick()
        assertEquals(1, opened)
    }

    @Test fun detailScreen_localTitle_hasNoDownloadControls_andPlaysOffline() {
        var plays = 0
        val state = ItemDetailUiState(found = true, itemId = local, title = "Momo", author = "Ende", detailsLoaded = true, playableOffline = true, isLocal = true)
        rule.setContent { CompositionLocalProvider(LocalOfflineDimming provides true) { LauschigTheme { ItemDetailScreen(state, {}, { plays++ }, {}, {}, {}, {}) } } }
        rule.onNodeWithTag("localSource").assertIsDisplayed()
        rule.onNodeWithText("Download").assertDoesNotExist()
        rule.onNodeWithText("Play").assertIsEnabled().performClick()
        assertEquals(1, plays)
    }

    @Test fun detailViewModel_localTitle_needsNoAccount_andIsPlayableOffline() = runTest {
        db.itemDao().upsertDetails(ItemEntity(dev, local, DeviceLibrary.LIBRARY_ID, "Momo", durationSec = 100.0))
        val loads = mutableListOf<String>()
        val player = Player()
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl"), db.audioFileDao())) { false }
        val vm = viewModels.get(
            viewModelFactory {
                initializer {
                    ItemDetailViewModel(local, FakeAccountStore(), db.itemDao(), NoDownloads, player, flowOf(ConnectionState.Offline), { s, i -> loads += "$s/$i" }, covers)
                }
            },
            ItemDetailViewModel::class,
        )
        val s = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { vm.state.first { it.found } } }
        assertTrue(s.isLocal)
        assertTrue(s.playableOffline)
        vm.onPrimary()
        assertEquals(listOf("$dev/$local"), player.plays)
        assertTrue(loads.isEmpty())
    }

    @Test fun extras_localBookmarks_liveOnTheDevice_andTriggerNoSync() = runTest {
        val triggers = mutableListOf<String>()
        val sync = object : SyncTrigger {
            override fun syncNow() { triggers += "full" }
            override fun syncBookmarks() { triggers += "bookmarks" }
        }
        val store = FakeAccountStore().apply {
            acc.value = io.wolftown.lauschig.account.Account("aaaaaaaaaaaaaaaa", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet())
        }
        val vm = viewModels.own(
            TitleExtrasViewModel(
                store, db.bookmarkDao(), HistoryRepository(flowOf(null), db.historyDao(), db.sessionDao()), db.queueDao(),
                db.itemDao(), db.episodeDao(), Player(), flowOf(ConnectionState.Offline), sync,
            ),
        )
        vm.addBookmark(local, 42.7, "Stelle")
        val row = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { vm.bookmarks(local).first { it.isNotEmpty() } } }.single()
        assertEquals(dev, row.serverId)
        assertEquals(42.0, row.timeSec, 0.0)
        assertTrue(db.bookmarkDao().all("aaaaaaaaaaaaaaaa").isEmpty())
        vm.deleteBookmark(row)
        assertTrue(triggers.isEmpty())
        // Queued in the account's queue, described from the device library.
        db.itemDao().upsertDetails(ItemEntity(dev, local, DeviceLibrary.LIBRARY_ID, "Momo", author = "Ende"))
        vm.enqueue(local)
        val rows = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { vm.queueRows.first { it.isNotEmpty() } } }
        assertEquals("Momo", rows.single().title)
        assertEquals(listOf(local), db.queueDao().entries("aaaaaaaaaaaaaaaa").map { it.itemKey })
    }
}

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = collectAsState().value
