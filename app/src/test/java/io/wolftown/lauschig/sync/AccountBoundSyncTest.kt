package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.admin.AdminRepository
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.history.HistoryRepository
import io.wolftown.lauschig.net.AccountChangedException
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.net.TwoUsersServer
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_A
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_B
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.playback.ProgressActions
import io.wolftown.lauschig.settings.AutoDownloadMode
import io.wolftown.lauschig.ui.browse.AuthorInfoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review focus 2 of Phase 7: a late sync or worker of account A, after the switch to B, against one server with two users.
 * It never sends as B, and it never writes B's answer into A's rows. Everything is real but the server: [ApiProvider], clients, Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountBoundSyncTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val web = TwoUsersServer()
    private val a = web.a.serverId
    private val b = web.b.serverId
    private lateinit var db: LauschigDatabase
    private lateinit var store: FakeAccountStore
    private lateinit var api: ApiProvider
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stamps = InMemorySyncStamps()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        store = web.store()
        api = web.api(store)
        web.keepOnline(api, scope)
        // A has a library (the collection sync goes by the libraries it knows) and something waiting for the server.
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(a, "a-lib", "a", "book", 0)))
        db.itemDao().upsertFromList(listOf(ItemEntity(a, "a-book", "a-lib", "Title a", durationSec = 100.0)))
        db.progressDao().saveLocal(a, "a-book", 42.0, 100.0, false, 5_000)
        db.sessionDao().upsert(LocalSessionEntity("a-open", a, "a-book", 0.0, 42.0, 42.0, 1_000, 2_000))
        db.bookmarkDao().add(a, "a-book", 7.0, "note", 1)
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
        web.shutdown()
    }

    /** The switch as the store does it; the connection follows (reset, then B's own answer). */
    private suspend fun switchToB() {
        assertTrue(store.activate(b))
        web.keepOnline(api, scope)
        web.seen.clear()
    }

    private fun progressSync() = ProgressSync(api::meApi, db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") }, stamps = stamps)
    private fun bookmarkSync() = BookmarkSync(api::meApi, db.bookmarkDao())
    private fun librarySync() = LibrarySync(api::libraryApi, db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { it() })
    private fun collectionSync() = CollectionSync(api::libraryApi, db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao(), stamps) { it() }
    private fun listeningSync() = ListeningSessionSync(api::meApi, db.listeningSessionDao(), db.sessionDao(), stamps, { true }, scope = scope)
    private fun history() = HistoryRepository(api::meApi, db.historyDao(), db.sessionDao())

    private fun assertNothingSent() = assertEquals(emptyList<TwoUsersServer.Seen>(), web.seen.toList())

    /** A's rows as they were set up: B's answer (a "b-…" row or position) landed nowhere. */
    private suspend fun assertARowsUntouched() {
        val p = db.progressDao().get(a, "a-book")!!
        assertEquals(42.0, p.currentTimeSec, 0.0)
        assertTrue("A's change still waits for its account", p.dirty)
        assertEquals(listOf("a-open"), db.sessionDao().unsynced(a).map { it.id })
        assertEquals(listOf(BookmarkPending.UPSERT), db.bookmarkDao().pending(a).map { it.pending })
        assertNull(db.progressDao().get(a, "b-book"))
        assertNull(db.itemDao().get(a, "b-book"))
        assertEquals(listOf("a-lib"), db.libraryDao().getAll(a).map { it.id })
        assertEquals(emptyList<String>(), db.listeningSessionDao().ids(a))
    }

    @Test fun progressSync_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        assertEquals(emptyList<PositionJump>(), progressSync().sync(a))
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun bookmarkSync_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        bookmarkSync().sync(a)
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun librarySync_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        librarySync().syncAll(a)
        librarySync().loadDetails(a, "a-book")
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun collectionSync_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        collectionSync().sync(a)
        assertNothingSent()
        assertEquals(0, db.collectionDao().observeByKind(a, io.wolftown.lauschig.db.entities.CollectionKind.COLLECTION).first().size)
    }

    @Test fun listeningSync_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        listeningSync().sync(a)
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun history_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        history().refresh(a, "a-book")
        assertNothingSent()
        assertEquals(0, history().observe(a, "a-book").first().entries.count { it.id == "a-history" || it.id == "b-history" })
    }

    @Test fun pullingProgress_ofTheLeftAccount_sendsNothing_writesNothing() = runBlocking {
        switchToB()
        val actions = ProgressActions(db.progressDao(), db.itemDao(), db.episodeDao(), NoPlayer, api::meApi, object : SyncTrigger { override fun syncNow() {} })
        assertEquals(false, actions.pullFromServer(a, "a-book"))
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun adminCalls_onlyEverGoOutForTheSignedInAccount() = runBlocking {
        switchToB()
        val admin = AdminRepository(api::adminApi, api::libraryApi, flowOf(web.a), { _, _ -> null }, { _, _ -> }, {})
        // Its idea of the account is still A's (a page that survived the switch): nothing goes out.
        assertEquals(io.wolftown.lauschig.admin.AdminResult.Offline, admin.onlineUsers())
        assertNothingSent()
    }

    @Test fun authors_ofTheLeftAccount_waitForIt_andNeverAskAsTheNewOne() = runBlocking {
        switchToB()
        val cache = AuthorInfoCache(api::libraryApi) { listOf("a-lib") }
        val load = kotlinx.coroutines.withTimeoutOrNull(300) { cache.ensureLoaded(a) }
        assertNull("waits for its own account", load)
        assertNothingSent()
        assertEquals(emptyMap<String, Any>(), cache.authors.value)
    }

    @Test fun theSignedInAccount_syncsWithItsOwnToken_intoItsOwnRows() = runBlocking {
        switchToB()
        progressSync().sync(b)
        bookmarkSync().sync(b)
        librarySync().syncAll(b)
        collectionSync().sync(b)
        listeningSync().sync(b)
        history().refresh(b, "b-book")

        assertEquals(listOf<String?>(BEARER_B), web.tokens())
        assertEquals(10.0, db.progressDao().get(b, "b-book")!!.currentTimeSec, 0.0)
        assertEquals(listOf("b-lib"), db.libraryDao().getAll(b).map { it.id })
        assertNotNull(db.itemDao().get(b, "b-book"))
        assertEquals(listOf("b-session"), db.listeningSessionDao().ids(b))
        // And A's rows wait for A.
        assertARowsUntouched()
    }

    @Test fun back_onTheOldAccount_itsChangesGoUpWithItsOwnToken() = runBlocking {
        switchToB()
        progressSync().sync(a) // nothing: not its turn
        bookmarkSync().sync(a)
        assertTrue(store.activate(a))
        web.keepOnline(api, scope)
        web.seen.clear()

        progressSync().sync(a)
        bookmarkSync().sync(a)

        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertTrue(web.seen.any { it.method == "PATCH" && it.path == "/api/me/progress/a-book" })
        assertTrue(web.seen.any { it.method == "POST" && it.path == "/api/session/local-all" })
        assertTrue(web.seen.any { it.method == "POST" && it.path == "/api/me/item/a-book/bookmark" })
        assertEquals(false, db.progressDao().get(a, "a-book")!!.dirty)
        assertEquals(emptyList<String>(), db.sessionDao().unsynced(a).map { it.id })
    }

    /** A run that holds A's API when the switch comes: what it already asked is A's, the rest does not go out. */
    @Test fun aSwitchInTheMiddleOfASync_stopsItsRequests_noneRunsAsTheNewAccount() = runBlocking {
        web.seen.clear()
        web.onRequest = { r -> if (r.path == "/api/me") runBlocking { store.activate(b) } }
        var failed: Throwable? = null
        try {
            progressSync().sync(a)
        } catch (e: Exception) {
            failed = e
        }
        web.onRequest = {}
        assertTrue("the next request is refused: $failed", failed is AccountChangedException)
        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertEquals(1, web.seen.size)
        assertARowsUntouched()
    }

    // The workers read the account once, then wait for the connection: the switch comes in that gap.

    private val noDownloads = object : DownloadManager {
        val queued = mutableListOf<String>()
        override suspend fun enqueue(serverId: String, itemId: String, network: androidx.work.NetworkType?) { queued += "$serverId:$itemId" }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }
    private val bus = PositionJumpBus()

    /** The gate of a worker that started as A: the switch comes while it waits. */
    private val switchWhileWaiting = OnlineGate { switchToB(); true }

    private inline fun <reified W : ListenableWorker> worker(crossinline make: (Context, WorkerParameters) -> W): W =
        TestListenableWorkerBuilder<W>(ctx).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = make(c, p)
        }).build()

    private fun syncWorker(gate: OnlineGate) = worker { c, p ->
        SyncWorker(
            c, p, store, librarySync(), progressSync(), bus, gate,
            EpisodeAutoDownloader(flowOf(AutoDownloadMode.entries.first { it.count > 0 }), db.episodeDao(), db.progressDao(), db.downloadDao(), noDownloads, { emptySet() }, {}),
            bookmarkSync(), collectionSync(), listeningSync(),
        )
    }

    @Test fun syncWorker_startedAsA_afterTheSwitch_sendsNothing_writesNothing() = runBlocking {
        db.episodeDao().upsert(listOf(io.wolftown.lauschig.db.entities.EpisodeEntity(a, "a-pod", "ep", "Folge", recent = true)))
        assertEquals(ListenableWorker.Result.success(), syncWorker(switchWhileWaiting).doWork())
        assertNothingSent()
        assertARowsUntouched()
        assertEquals("nothing queued for A while B is active", emptyList<String>(), noDownloads.queued)
    }

    @Test fun progressWorker_startedAsA_afterTheSwitch_sendsNothing_writesNothing() = runBlocking {
        val w = worker { c, p -> ProgressSyncWorker(c, p, store, progressSync(), bus, switchWhileWaiting) }
        assertEquals(ListenableWorker.Result.success(), w.doWork())
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun bookmarkWorker_startedAsA_afterTheSwitch_sendsNothing_writesNothing() = runBlocking {
        val w = worker { c, p -> BookmarkSyncWorker(c, p, store, switchWhileWaiting, bookmarkSync()) }
        assertEquals(ListenableWorker.Result.success(), w.doWork())
        assertNothingSent()
        assertARowsUntouched()
    }

    @Test fun aWorkerStartedAfterTheSwitch_isTheNewAccounts() = runBlocking {
        switchToB()
        assertEquals(ListenableWorker.Result.success(), syncWorker(OnlineGate { true }).doWork())
        assertEquals(listOf<String?>(BEARER_B), web.tokens())
        assertNotNull(db.itemDao().get(b, "b-book"))
        assertARowsUntouched()
    }

    /** A's sync finishes right after the switch: its jump does not ask B to go back to a position of A's title. */
    @Test fun aJumpOfTheLeftAccount_isNotShownToTheNewOne() = runBlocking {
        db.progressDao().saveLocal(b, "b-book", 500.0, 100.0, false, 1)
        db.progressDao().markSynced(b, "b-book", 1)
        web.seen.clear()
        web.onRequest = { r -> if (r.path == "/api/me") runBlocking { store.activate(a) } } // B's sync, the switch to A in it
        assertTrue(store.activate(b))
        web.keepOnline(api, scope)
        val w = worker { c, p -> ProgressSyncWorker(c, p, store, ProgressSync(api::meApi, db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") }, stamps = stamps), bus, OnlineGate { true }) }
        w.doWork()
        web.onRequest = {}
        assertEquals(emptyList<PositionJump>(), bus.pending.value)
    }

    private object NoPlayer : PlayerController {
        override val state = MutableStateFlow(PlayerUiState())
        override fun play(serverId: String, itemId: String, startSec: Double?) { fail("nothing plays") }
        override fun togglePlayPause() {}
        override fun pause() {}
        override fun seekBy(deltaSec: Int) {}
        override fun seekTo(globalSec: Double) {}
        override fun seekToChapter(index: Int) {}
        override fun setSpeed(speed: Float) {}
    }
}
