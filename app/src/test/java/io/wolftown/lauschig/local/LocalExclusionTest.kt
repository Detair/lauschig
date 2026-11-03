package io.wolftown.lauschig.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.library.BookMediaDto
import io.wolftown.lauschig.absapi.library.BookMetadataDto
import io.wolftown.lauschig.absapi.library.CollectionsPage
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryDto
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.absapi.library.PlaylistsResponse
import io.wolftown.lauschig.absapi.me.BookmarkBody
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.BookmarksResponse
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.ListeningSessionsPage
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.download.MeteredDownload
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.history.HistoryRepository
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.NextVolumePrefetcher
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.playback.ProgressActions
import io.wolftown.lauschig.queue.QueueEntryState
import io.wolftown.lauschig.queue.queueEntryState
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.settings.NextVolumeMode
import io.wolftown.lauschig.sync.BookmarkSync
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.common.ProgressEntry
import io.wolftown.lauschig.ui.common.progressEntries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Every call that would reach the server; a test fails if one of them carries a local title. */
private class RecordingMe : MeApi {
    val calls = mutableListOf<String>()
    override suspend fun me() = AbsUser("u", "n", "user").also { calls += "me" }
    override suspend fun updateProgress(itemId: String, u: ProgressUpdate) { calls += "progress $itemId" }
    override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) { calls += "episode $itemId $episodeId" }
    override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult {
        calls += "sessions " + body.sessions.joinToString(",") { it.libraryItemId }
        return LocalSessionsResult(emptyList())
    }
    override suspend fun bookmarks() = BookmarksResponse(emptyList()).also { calls += "bookmarks" }
    override suspend fun createBookmark(itemId: String, body: BookmarkBody): BookmarkDto { calls += "create $itemId"; return BookmarkDto(itemId, body.time, body.title, 1) }
    override suspend fun updateBookmark(itemId: String, body: BookmarkBody): BookmarkDto { calls += "update $itemId"; return BookmarkDto(itemId, body.time, body.title, 1) }
    override suspend fun deleteBookmark(itemId: String, time: String) { calls += "delete $itemId" }
    override suspend fun itemListeningSessions(itemId: String, itemsPerPage: Int, page: Int) = ListeningSessionsPage().also { calls += "history $itemId" }
    override suspend fun episodeListeningSessions(itemId: String, episodeId: String, itemsPerPage: Int, page: Int) = ListeningSessionsPage().also { calls += "history $itemId" }
}

private class RecordingLibrary(private val serverItems: List<String>) : LibraryApi {
    val calls = mutableListOf<String>()
    override suspend fun libraries() = LibrariesResponse(listOf(LibraryDto("l1", "Bücher", "book", 0))).also { calls += "libraries" }
    override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int): LibraryItemsPage {
        calls += "items $libraryId $page"
        val chunk = if (page == 0) serverItems.map { LibraryItemDto(it, "l1", "book", 1, BookMediaDto(BookMetadataDto(title = "T$it"), duration = 10.0)) } else emptyList()
        return LibraryItemsPage(chunk, serverItems.size, limit, page)
    }
    override suspend fun authors(id: String) = error("unused")
    override suspend fun recentEpisodes(id: String, limit: Int, page: Int) = error("unused")
    override suspend fun collections(libraryId: String): CollectionsPage = error("unused")
    override suspend fun playlists(): PlaylistsResponse = error("unused")
    override suspend fun item(id: String, expanded: Int): LibraryItemDto { calls += "item $id"; error("must not be asked") }
}

private class RecordingDownloads : DownloadManager {
    val enqueued = mutableListOf<String>()
    override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) { enqueued += "$serverId/$itemId" }
    override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
    override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = emptyFlow()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalExclusionTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val dev = DeviceLibrary.SERVER_ID
    private val local = DeviceLibrary.titleId("content://tree/x", "dir:a")
    private val local2 = DeviceLibrary.titleId("content://tree/x", "dir:b")
    private val me = RecordingMe()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun assertNoLocalCall(calls: List<String>) =
        assertTrue("a local title reached the server: $calls", calls.none { "local:" in it || " $dev" in it })

    private suspend fun seedLocal() {
        db.itemDao().upsertDetails(ItemEntity(dev, local, DeviceLibrary.LIBRARY_ID, "Momo", durationSec = 100.0, seriesName = "Saga", seriesSortKey = 1.0))
        db.itemDao().upsertDetails(ItemEntity(dev, local2, DeviceLibrary.LIBRARY_ID, "Momo 2", durationSec = 100.0, seriesName = "Saga", seriesSortKey = 2.0))
    }

    // --- Progress and sessions -------------------------------------------------------------------------------------

    @Test fun progressSync_neverPushesOrUploadsLocalTitles_evenUnderTheAccountServer() = runTest {
        seedLocal()
        db.progressDao().saveLocal(dev, local, 10.0, 100.0, false, 5)
        db.sessionDao().upsert(LocalSessionEntity("sd", dev, local, 0.0, 10.0, 10.0, 1, 2))
        // A local key that ended up under the account's server by a bug is still never sent.
        db.progressDao().saveLocal(s, local, 10.0, 100.0, false, 5)
        db.sessionDao().upsert(LocalSessionEntity("sa", s, local, 0.0, 10.0, 10.0, 1, 2))
        db.progressDao().saveLocal(s, "li_1", 20.0, 100.0, false, 5)
        val sync = ProgressSync(flowOf(me), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") })
        sync.sync(s)
        assertNoLocalCall(me.calls)
        assertTrue(me.calls.contains("progress li_1"))
        assertTrue(db.progressDao().dirty(dev).single().dirty)
        assertEquals(2, db.sessionDao().unsynced(dev).size + db.sessionDao().unsynced(s).size)
        me.calls.clear()
        sync.sync(dev)
        assertTrue("the device server is never synced: ${me.calls}", me.calls.isEmpty())
    }

    // --- Bookmarks -------------------------------------------------------------------------------------------------

    @Test fun bookmarks_ofLocalTitles_areDeviceOnly_andNeverSent() = runTest {
        assertFalse(BookmarkRules.isSyncable(local))
        db.bookmarkDao().save(dev, local, 10.0, "n", now = 1)
        db.bookmarkDao().save(s, local, 20.0, "bug", now = 1)
        assertEquals(BookmarkPending.NONE, db.bookmarkDao().get(dev, local, 10.0)!!.pending)
        assertEquals(BookmarkPending.NONE, db.bookmarkDao().get(s, local, 20.0)!!.pending)
        db.bookmarkDao().delete(dev, local, 10.0)
        assertNull("deleted at once, no tombstone", db.bookmarkDao().get(dev, local, 10.0))
        val sync = BookmarkSync(flowOf(me), db.bookmarkDao())
        sync.sync(s)
        assertNoLocalCall(me.calls)
        // The pull keeps the local row: the server never knows it.
        assertNotNull(db.bookmarkDao().get(s, local, 20.0))
        me.calls.clear()
        sync.sync(dev)
        assertTrue(me.calls.isEmpty())
    }

    // --- History ---------------------------------------------------------------------------------------------------

    @Test fun history_ofLocalTitles_isNeverFetched() = runTest {
        val repo = HistoryRepository(flowOf(me), db.historyDao(), db.sessionDao())
        repo.refresh(dev, local)
        repo.refresh(s, local)
        assertTrue(me.calls.isEmpty())
        db.sessionDao().upsert(LocalSessionEntity("sd", dev, local, 0.0, 30.0, 30.0, 1, 2))
        assertEquals(1, repo.observe(dev, local).first().entries.size)
    }

    // --- Library sync ----------------------------------------------------------------------------------------------

    @Test fun librarySync_neverAsksForLocalDetails_andNeverDeletesDeviceRows() = runTest {
        seedLocal()
        db.progressDao().saveLocal(dev, local, 10.0, 100.0, false, 5)
        val api = RecordingLibrary(listOf("li_1"))
        val sync = LibrarySync(flowOf(api), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { block -> db.withTransaction { block() } })
        sync.syncAll(s)
        sync.syncAll(dev)
        sync.loadDetails(dev, local)
        sync.loadDetails(s, local)
        sync.loadDetailsForPlayable(dev, local)
        sync.loadEpisodes(dev, local)
        assertNoLocalCall(api.calls)
        assertEquals("one full sync of the account only", 1, api.calls.count { it == "libraries" })
        assertNotNull(db.itemDao().get(dev, local))
        assertNotNull(db.itemDao().get(dev, local2))
        assertNotNull(db.itemDao().get(s, "li_1"))
    }

    // --- Downloads -------------------------------------------------------------------------------------------------

    @Test fun downloadManager_neverQueuesOrDeletesALocalTitle() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        seedLocal()
        db.localLibraryDao().putTitleRows(
            io.wolftown.lauschig.db.dao.LocalTitleRows(
                io.wolftown.lauschig.db.entities.LocalTitleEntity(local, "content://tree/x", "dir:a", "a", "sig", 1),
                ItemEntity(dev, local, DeviceLibrary.LIBRARY_ID, "Momo", detailsLoaded = true),
                listOf(io.wolftown.lauschig.db.entities.AudioFileEntity(dev, local, 1, "doc", 10.0, 5, "mp3", "content://fake/doc", 5)),
                emptyList(),
            ),
        )
        val files = DownloadFiles(File(ctx.cacheDir, "excl-${System.nanoTime()}"), db.audioFileDao())
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        mgr.enqueue(dev, local)
        mgr.enqueue(s, local, NetworkType.CONNECTED)
        assertNull(db.downloadDao().get(dev, local))
        assertNull(db.downloadDao().get(s, local))
        val wm = WorkManager.getInstance(ctx)
        assertTrue(wm.getWorkInfosForUniqueWork("download-$dev-$local").get().isEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork("download-$s-$local").get().isEmpty())
        // "Delete download" must never touch the user's own files or their content:// paths.
        mgr.cancel(dev, local, deleteFiles = true)
        assertEquals("content://fake/doc", db.audioFileDao().forItem(dev, local).single().localPath)
    }

    @Test fun downloadOnPlay_ignoresLocalTitles() = runTest {
        val downloads = RecordingDownloads()
        val prompts = DownloadPrompts()
        val starter = PlayDownloadStarter(
            enabled = flowOf(true), metered = MutableStateFlow(MeteredDownload.ASK), setMetered = {}, isMetered = { true },
            row = { _, _ -> null }, describe = { _, _ -> "x" to 1L }, downloads = downloads, prompts = prompts, scope = backgroundScope,
        )
        starter.onStreamStarted(dev, local)
        starter.onStreamStarted(s, local)
        assertTrue(downloads.enqueued.isEmpty())
        assertNull(prompts.prompt.value)
    }

    @Test fun nextVolumePrefetch_skipsLocalTitles() = runTest {
        seedLocal()
        val settings = AppSettings(ctx).apply { setNextVolumeMode(NextVolumeMode.PREFETCH) }
        val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val player = object : PlayerController {
            override val state = MutableStateFlow(PlayerUiState(itemId = local, durationSec = 100.0, positionSec = 90.0))
            override fun play(serverId: String, itemId: String, startSec: Double?) {}
            override fun togglePlayPause() {}
            override fun pause() {}
            override fun seekBy(deltaSec: Int) {}
            override fun seekTo(globalSec: Double) {}
            override fun seekToChapter(index: Int) {}
            override fun setSpeed(speed: Float) {}
        }
        val downloads = RecordingDownloads()
        NextVolumePrefetcher(player, settings, accounts, db.itemDao(), db.downloadDao(), downloads)
            .start(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        testScheduler.advanceUntilIdle()
        assertTrue(downloads.enqueued.isEmpty())
    }

    // --- Progress actions ------------------------------------------------------------------------------------------

    @Test fun progressActions_onLocalTitles_stayOnTheDevice() = runTest {
        seedLocal()
        var syncs = 0
        val player = object : PlayerController {
            override val state = MutableStateFlow(PlayerUiState())
            override fun play(serverId: String, itemId: String, startSec: Double?) {}
            override fun togglePlayPause() {}
            override fun pause() {}
            override fun seekBy(deltaSec: Int) {}
            override fun seekTo(globalSec: Double) {}
            override fun seekToChapter(index: Int) {}
            override fun setSpeed(speed: Float) {}
        }
        val actions = ProgressActions(db.progressDao(), db.itemDao(), db.episodeDao(), player, flowOf(me), object : SyncTrigger { override fun syncNow() { syncs++ } }, settleMs = 0)
        assertFalse(actions.pullFromServer(dev, local))
        actions.markFinished(dev, local)
        actions.startOver(dev, local)
        actions.hideFromContinue(dev, local)
        assertTrue(me.calls.isEmpty())
        assertEquals("no sync is started for a device title", 0, syncs)
        assertFalse(db.progressDao().get(dev, local)!!.isFinished)
        assertFalse(ProgressEntry.PullFromServer in progressEntries(null, offline = false, local = true))
        assertTrue(ProgressEntry.PullFromServer in progressEntries(null, offline = false, local = false))
    }

    // --- Queue and last title --------------------------------------------------------------------------------------

    @Test fun queue_aLocalKeyResolvesToTheDeviceTitle_andIsPlayableOffline() = runTest {
        seedLocal()
        assertEquals(QueueEntryState.PLAYABLE, queueEntryState(db.itemDao(), db.episodeDao(), db.downloadDao(), online = false, s, local))
        assertEquals(QueueEntryState.MISSING, queueEntryState(db.itemDao(), db.episodeDao(), db.downloadDao(), online = true, s, DeviceLibrary.titleId("x", "gone")))
    }

    @Test fun lastTitle_canBeALocalTitle() = runTest {
        seedLocal()
        db.progressDao().saveLocal(dev, local, 10.0, 100.0, false, 5)
        val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val store = object : LastPlayedStore {
            override suspend fun get() = LastPlayed(dev, local, 7)
            override suspend fun set(last: LastPlayed) {}
            override suspend fun clear() {}
        }
        val player = object : PlayerController {
            override val state = MutableStateFlow(PlayerUiState())
            override fun play(serverId: String, itemId: String, startSec: Double?) {}
            override fun togglePlayPause() {}
            override fun pause() {}
            override fun seekBy(deltaSec: Int) {}
            override fun seekTo(globalSec: Double) {}
            override fun seekToChapter(index: Int) {}
            override fun setSpeed(speed: Float) {}
        }
        assertEquals(LastTitleStarter.Last(dev, local, 7), LastTitleStarter(accounts, store, db.progressDao(), db.itemDao(), db.episodeDao(), player).last())
    }

    @Test fun serverFor_routesLocalIdsToTheDevice() {
        assertEquals(dev, DeviceLibrary.serverFor(s, local))
        assertEquals(dev, DeviceLibrary.serverFor(null, local))
        assertEquals(s, DeviceLibrary.serverFor(s, "li_1"))
        assertTrue(DeviceLibrary.isValidId(local))
        assertFalse(DeviceLibrary.isValidId("local:../../x"))
    }
}
