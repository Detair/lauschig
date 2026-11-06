package io.wolftown.lauschig.account

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.connection.AddressResolver
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.OkHttpReachabilityProbe
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.download.DownloadEnvironment
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadNotifier
import io.wolftown.lauschig.download.DownloadPrompt
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.download.DownloadWorker
import io.wolftown.lauschig.download.SwitchedDownloads
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.TwoUsersServer
import io.wolftown.lauschig.MainViewModel
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.download.MeteredDownload
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_A
import io.wolftown.lauschig.ui.AppViewModel
import io.wolftown.lauschig.ui.nav.Start
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.ProfileGate
import io.wolftown.lauschig.ui.profiles.ProfileOperations
import io.wolftown.lauschig.ui.profiles.ProfileOutcome
import io.wolftown.lauschig.ui.profiles.ProfileWork
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.profiles.RemovalFacts
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_B
import io.wolftown.lauschig.net.TwoUsersServer.Companion.TOKEN_A
import io.wolftown.lauschig.net.TwoUsersServer.Companion.TOKEN_B
import io.wolftown.lauschig.playback.DataStoreLastPlayedStore
import io.wolftown.lauschig.playback.FakeFacade
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.playback.LastPlayedMigration
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlayerControllerImpl
import io.wolftown.lauschig.playback.ShakeSource
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.playback.StreamMonitor
import io.wolftown.lauschig.playback.forAccount
import io.wolftown.lauschig.playback.loadPlayable
import io.wolftown.lauschig.rmab.RmabRepository
import io.wolftown.lauschig.settings.AutoDownloadMode
import io.wolftown.lauschig.sync.BookmarkSync
import io.wolftown.lauschig.sync.CollectionSync
import io.wolftown.lauschig.sync.EpisodeAutoDownloader
import io.wolftown.lauschig.sync.InMemorySyncStamps
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.sync.OnlineGate
import io.wolftown.lauschig.sync.PositionJump
import io.wolftown.lauschig.sync.PositionJumpBus
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.sync.SyncWorker
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 7, Task 1: the switch with everything real but the server and the media player's service: the store with
 * encrypted tokens, the provider and its clients, Room, the syncs, the download worker, the sign-out flow as Hilt builds it
 * (its `@Inject` constructor), the player controller and every per-account store. [FakeFacade] stands in for the service,
 * its pause does what the tracker does: the position lands a moment later. Two users on one [TwoUsersServer]: A plays and downloads, the switch to B, a late
 * worker of A, the way back. Asserted per request: whose token. Per row: whose account.
 *
 * The rule under test: after a switch nothing of A runs, sends or writes as B, and nothing of A is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileSwitchFlowTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val web = TwoUsersServer()
    private val a = web.a.serverId
    private val b = web.b.serverId
    private val dir = File(ctx.cacheDir, "flow-${System.nanoTime()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private fun prefsFile(name: String) = File(dir, "$name.preferences_pb")
    private fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { prefsFile(name) }

    private val store = DataStoreAccountStore(prefs("account"), cipher)
    private val active: Flow<String?> = store.account.map { it?.serverId }
    private val db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val root = File(dir, "downloads").apply { mkdirs() }
    private val files = DownloadFiles(root, db.audioFileDao())
    private val stamps = InMemorySyncStamps()
    private val reachable = MutableStateFlow(true)
    private val api: ApiProvider = web.api(store, reachable)
    private val keepAlive = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val progressSync = ProgressSync(api::meApi, db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "tablet") }, stamps = stamps)
    private val bookmarkSync = BookmarkSync(api::meApi, db.bookmarkDao())
    private val librarySync = LibrarySync(api::libraryApi, db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { it() })
    private val collectionSync = CollectionSync(api::libraryApi, db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao(), stamps) { it() }
    private val listeningSync = ListeningSessionSync(api::meApi, db.listeningSessionDao(), db.sessionDao(), stamps, { true }, scope = scope)

    /** Queues like the real manager (the row) and records what WorkManager would get. */
    private val queued = mutableListOf<Triple<String, String, NetworkType?>>()
    private val manager = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) {
            queued += Triple(serverId, itemId, network)
            db.downloadDao().upsert(DownloadEntity(serverId, itemId, DownloadState.QUEUED, 1))
        }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }
    private val switched = SwitchedDownloads(prefs("switched"), db.downloadDao(), manager)

    private val rmabStore: DataStore<Preferences> by lazy { prefs("rmab") }
    private val rmab by lazy { RmabRepository(rmabStore, cipher, { "Tablet" }, active) }
    private val searchesStore by lazy { prefs("searches") }
    private val searches by lazy { RecentSearches(searchesStore, active) }
    /** The last title; lazy, as a test about the upgrade writes the old file first. */
    private val lastPlayed by lazy {
        DataStoreLastPlayedStore(PreferenceDataStoreFactory.create(migrations = listOf(LastPlayedMigration), scope = scope) { prefsFile("last_played") })
    }
    private val oidcStore by lazy { prefs("oidc") }
    private val prompts = DownloadPrompts()
    private val jumps = PositionJumpBus()
    private var diskCovers = 0
    private var memoryCovers = 0
    private var logs = 0
    private val cleaner by lazy {
        AccountStateCleaner(
            rmab, OidcLogin(oidcStore, cipher, OkHttpClient()), searches, prompts,
            clearImageCaches = { diskCovers++ }, cancelNotifications = {}, clearDiagnostics = { logs++ },
            clearMemoryCovers = { memoryCovers++ }, clearMoments = { jumps.clear() }, adoptDeviceTitle = lastPlayed::adoptDeviceEntry,
            removeProtection = locks::remove,
        )
    }
    private val locks by lazy { DataStoreProfileLocks(prefs("locks"), cipher, { 1_000L }, 1_000) }

    private val facade = FakeFacade()
    private val streams = mutableListOf<String>()
    private val player = PlayerControllerImpl(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        facade = facade,
        factory = MediaItemFactory({ true }) { _, _ -> null },
        loadItem = { sid, id -> loadPlayable(db.itemDao(), db.episodeDao(), sid, id) },
        onlineBaseUrl = { web.server.url("/") },
        coverFile = { _, _ -> null },
        loadDetails = { sid, id -> librarySync.loadDetails(sid, id) },
        io = Dispatchers.Unconfined,
        onStreamStarted = { sid, id -> streams += "$sid:$id" },
        isSignedIn = { sid -> store.account.first()?.serverId == sid },
    )
    private val starter by lazy { LastTitleStarter(store, lastPlayed, db.progressDao(), db.itemDao(), db.episodeDao(), player) }

    /** What the tracker writes when the service pauses, for whichever title plays. */
    private var finalPosition = 45.0
    private var clock = 5_000L
    private val kept = FakeKeptAccounts()
    private val cancelled = mutableListOf<String>()
    private var syncs = 0
    /** Runs while the account's work is cancelled: the gap between the first stop and the account's change. */
    private var whileCancelling: suspend (serverId: String) -> Unit = {}

    /** `withContext(Dispatchers.Main.immediate)` of the production lambdas: not this thread, which blocks in the test. */
    private val mainThread = kotlinx.coroutines.newSingleThreadContext("test-main")

    init {
        Dispatchers.setMain(mainThread)
        facade.onPause = { title ->
            if (title != null) scope.launch {
                delay(TRACKER_SAVE_MS) // well inside the settle time of the sign-out flow; without it the push finds nothing new
                db.progressDao().saveLocal(title.first, title.second, finalPosition, 100.0, false, ++clock)
                db.sessionDao().upsert(LocalSessionEntity("session-${title.first}-$clock", title.first, title.second, 0.0, finalPosition, finalPosition, 1_000, clock))
            }
        }
    }

    private val sleepTimer by lazy {
        SleepTimer(player, object : ShakeSource {
            override fun start(onShake: () -> Unit) {}
            override fun stop() {}
        }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    private val serverLogout by lazy {
        val net = object : NetworkInfo {
            override val isConnected = flowOf(true)
            override suspend fun currentSsid(): String? = null
        }
        ServerLogout(
            { api.connectionState.value }, api.unauthenticatedClient(),
            resolve = AddressResolver(OkHttpReachabilityProbe(), net)::resolveElsewhere, profileClient = api::unauthenticatedClient,
        )
    }

    /** As Hilt builds it. */
    private val signOut by lazy {
        SignOut(
            store,
            DeviceAccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), files, stamps, {}),
            kept,
            object : AccountWork {
                override suspend fun cancel(serverId: String) {
                    cancelled += serverId
                    whileCancelling(serverId)
                }
                override suspend fun cancelOwn(serverId: String) { cancelled += "own:$serverId" }
            },
            lastPlayed, player, sleepTimer, progressSync, bookmarkSync, api, serverLogout, cleaner, switched,
            object : SyncTrigger { override fun syncNow() { syncs++ } },
            starter,
        )
    }

    /** A family tablet: A active, B set aside, each with their tokens. */
    private suspend fun signedIn(other: Account = web.b) {
        store.save(other, TOKEN_B, "b-refresh")
        store.add(web.a, TOKEN_A, "a-refresh")
        web.keepOnline(api, keepAlive)
    }

    private suspend fun rmabOf(serverId: String, user: String) = rmabStore.edit {
        it[stringPreferencesKey("url:$serverId")] = "https://rmab.example/"
        it[stringPreferencesKey("username:$serverId")] = user
        it[stringPreferencesKey("token:$serverId")] = "enc:rmab_$user"
    }

    private suspend fun downloadRow(server: String, id: String, state: DownloadState) = db.downloadDao().upsert(DownloadEntity(server, id, state, 1))
    private suspend fun state(server: String, id: String) = db.downloadDao().get(server, id)?.state

    /** The real worker with the provider's clients. */
    private suspend fun downloadWorker(server: String, item: String): ListenableWorker.Result {
        val env = DownloadEnvironment(api.connection, { store.account.first()?.serverId }, api::clientFor)
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = DownloadWorker(
                c, p, env, OnlineGate { true }, librarySync, db.itemDao(), db.episodeDao(), db.audioFileDao(), db.downloadDao(), files, DownloadNotifier(c),
                StreamMonitor { 0L }, prompts, switched,
            )
        }
        return TestListenableWorkerBuilder<DownloadWorker>(ctx, workDataOf(DownloadWorker.KEY_SERVER to server, DownloadWorker.KEY_ITEM to item))
            .setWorkerFactory(factory).build().doWork()
    }

    private suspend fun syncWorker(gate: OnlineGate = OnlineGate { true }): ListenableWorker.Result {
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = SyncWorker(
                c, p, store, librarySync, progressSync, jumps, gate,
                EpisodeAutoDownloader(flowOf(AutoDownloadMode.OFF), db.episodeDao(), db.progressDao(), db.downloadDao(), manager, { emptySet() }, {}),
                bookmarkSync, collectionSync, listeningSync,
            )
        }
        return TestListenableWorkerBuilder<SyncWorker>(ctx).setWorkerFactory(factory).build().doWork()
    }

    /** A plays its title and downloads: one complete by the real worker, others running, waiting and paused by the user. */
    private suspend fun aListensAndDownloads() {
        librarySync.syncAll(a)
        downloadRow(a, "a-book", DownloadState.QUEUED)
        assertEquals(ListenableWorker.Result.success(), downloadWorker(a, "a-book"))
        assertEquals(DownloadState.COMPLETE, state(a, "a-book"))
        assertTrue(File(root, "$a/a-book").listFiles()!!.isNotEmpty())
        downloadRow(a, "a-running", DownloadState.RUNNING)
        downloadRow(a, "a-waiting", DownloadState.QUEUED)
        downloadRow(a, "a-paused", DownloadState.PAUSED)
        db.bookmarkDao().add(a, "a-book", 7.0, "note", 1)
        rmabOf(a, "fabi")
        searches.record("Die drei")
        player.play(a, "a-book")
        awaitPlaying("a-book")
        lastPlayed.set(LastPlayed(a, "a-book", 10_000))
        web.seen.clear()
    }

    private fun awaitPlaying(id: String) = runBlocking {
        kotlinx.coroutines.withTimeout(5_000) { player.state.first { it.itemId == id && it.isPlaying } }
    }

    /** The mini player has [id], paused (loaded from Room a moment after the switch). */
    private suspend fun awaitShownPaused(id: String) {
        val s = kotlinx.coroutines.withTimeout(5_000) { player.state.first { it.itemId == id } }
        assertFalse(s.isPlaying)
    }

    private suspend fun rowsOfA(): List<Any?> = listOf(
        db.itemDao().get(a, "a-book")?.title,
        db.progressDao().get(a, "a-book")?.currentTimeSec,
        db.bookmarkDao().all(a).map { it.timeSec },
        state(a, "a-book"),
        db.libraryDao().getAll(a).map { it.id },
    )

    @After fun tearDown() {
        keepAlive.cancel()
        scope.cancel()
        Dispatchers.resetMain()
        mainThread.close()
        db.close()
        web.shutdown()
        dir.deleteRecursively()
    }

    @Test fun thereAndBack_nothingRunsAsTheOtherAccount_nothingIsLost(): Unit = runBlocking {
        signedIn()
        aListensAndDownloads()

        // --- The switch to B.
        assertTrue(signOut.switchProfile(b))

        // The position was final before anything was sent, and everything went up as A.
        assertEquals("all as A", listOf<String?>(BEARER_A), web.tokens())
        val patch = web.seen.single { it.method == "PATCH" && it.path == "/api/me/progress/a-book" }
        assertTrue(patch.body, patch.body.contains("\"currentTime\":45.0"))
        assertTrue(web.seen.any { it.method == "POST" && it.path == "/api/session/local-all" })
        assertTrue(web.seen.any { it.method == "POST" && it.path == "/api/me/item/a-book/bookmark" })
        assertEquals(false, db.progressDao().get(a, "a-book")!!.dirty)
        assertFalse("no server logout at a switch", web.seen.any { it.path == "/logout" })

        // B is active; A stays signed in with its own tokens.
        assertEquals(b, store.account.first()!!.serverId)
        assertEquals(TOKEN_B, store.credentials()!!.accessToken)
        assertEquals(TOKEN_A, store.savedCredentials(a)!!.accessToken)
        assertEquals("a-refresh", store.savedCredentials(a)!!.refreshToken)

        // Nothing of A plays or shows: the player is empty, no mini player, no question, nothing that could start it.
        assertEquals(0, facade.items.size)
        assertEquals(null, player.state.value.itemId)
        assertNull(starter.last())
        assertNull(rmab.config.first())
        assertEquals(emptyList<String>(), searches.queries.first())
        assertEquals(listOf(a), cancelled)
        assertEquals(1, syncs)
        assertEquals("the covers on disk stay", 0, diskCovers)
        assertEquals(1, memoryCovers)
        assertEquals("the diagnostic log goes", 1, logs)

        // Nothing of A is lost: rows and files; the downloads wait.
        val aRows = rowsOfA()
        assertEquals(listOf("Title a", 45.0, listOf(5.0, 7.0), DownloadState.COMPLETE, listOf("a-lib")), aRows)
        assertTrue(File(root, "$a/a-book").listFiles()!!.isNotEmpty())
        assertEquals(DownloadState.PAUSED, state(a, "a-running"))
        assertEquals(DownloadState.PAUSED, state(a, "a-waiting"))
        assertEquals(DownloadState.PAUSED, state(a, "a-paused"))

        // --- A late worker of A: nothing goes out, nothing is written.
        web.seen.clear()
        db.progressDao().saveLocal(a, "a-book", 46.0, 100.0, false, ++clock) // the service's very last save lands now
        lastPlayed.set(LastPlayed(a, "a-book", 11_000))
        assertEquals(emptyList<PositionJump>(), progressSync.sync(a))
        bookmarkSync.sync(a)
        librarySync.syncAll(a)
        librarySync.loadDetails(a, "a-book")
        collectionSync.sync(a)
        listeningSync.sync(a)
        downloadRow(a, "a-late", DownloadState.QUEUED) // e.g. a download-on-play that started just before the switch
        assertEquals(ListenableWorker.Result.success(), downloadWorker(a, "a-late"))
        assertEquals(emptyList<TwoUsersServer.Seen>(), web.seen.toList())
        assertEquals(DownloadState.PAUSED, state(a, "a-late"))
        assertNull("no row of B's answer under A", db.itemDao().get(a, "b-book"))
        assertEquals(listOf("a-lib"), db.libraryDao().getAll(a).map { it.id })
        assertEquals(46.0, db.progressDao().get(a, "a-book")!!.currentTimeSec, 0.0)
        assertTrue("the late save waits for its account", db.progressDao().get(a, "a-book")!!.dirty)
        // A page that outlived the switch cannot start A's title under B either.
        player.play(a, "a-book")
        assertEquals(null, player.state.value.itemId)
        assertEquals(0, facade.items.size)
        assertNull("B's turn: its last title is not A's", starter.last())

        // --- B works: its token, its rows.
        assertEquals(ListenableWorker.Result.success(), syncWorker())
        assertEquals(listOf<String?>(BEARER_B), web.tokens())
        assertEquals(listOf("b-lib"), db.libraryDao().getAll(b).map { it.id })
        assertNotNull(db.itemDao().get(b, "b-book"))
        assertEquals(10.0, db.progressDao().get(b, "b-book")!!.currentTimeSec, 0.0)
        assertEquals(listOf("b-session"), db.listeningSessionDao().ids(b))
        assertEquals(emptyList<String>(), db.listeningSessionDao().ids(a))
        rmabOf(b, "matthias")
        searches.record("Bibi")
        downloadRow(b, "b-waiting", DownloadState.QUEUED)
        finalPosition = 77.0
        player.play(b, "b-book")
        awaitPlaying("b-book")
        lastPlayed.set(LastPlayed(b, "b-book", 12_000))
        web.seen.clear()
        queued.clear()

        // --- Back to A.
        assertTrue(signOut.switchProfile(a))

        // B's changes went up as B, the switch paused its playback and its download.
        assertEquals(listOf<String?>(BEARER_B), web.tokens())
        assertTrue(web.seen.single { it.method == "PATCH" && it.path == "/api/me/progress/b-book" }.body.contains("\"currentTime\":77.0"))
        assertEquals(DownloadState.PAUSED, state(b, "b-waiting"))

        // A has everything back: what the switch paused goes on, under the Wi-Fi rule; what the user paused stays.
        assertEquals(
            setOf(Triple(a, "a-running", null), Triple(a, "a-waiting", null), Triple(a, "a-late", null)),
            queued.toSet(),
        )
        assertEquals(DownloadState.PAUSED, state(a, "a-paused"))
        assertEquals("fabi", rmab.config.first()!!.username)
        assertEquals(listOf("Die drei"), searches.queries.first())
        assertEquals(TOKEN_A, store.credentials()!!.accessToken)
        assertEquals(TOKEN_B, store.savedCredentials(b)!!.accessToken)
        assertEquals(listOf(a, b), cancelled)

        // Its last title waits, paused, in the mini player; the car and the play keys find it; nothing plays.
        awaitShownPaused("a-book")
        assertEquals(46.0, player.state.value.positionSec, 0.0)
        assertEquals(0, facade.items.size)
        assertEquals("a-book", starter.last()!!.itemId)
        assertEquals(2, syncs)

        // Its late save goes up now, as A.
        web.seen.clear()
        progressSync.sync(a)
        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertTrue(web.seen.single { it.method == "PATCH" }.body.contains("\"currentTime\":46.0"))
        assertEquals(false, db.progressDao().get(a, "a-book")!!.dirty)

        // The play button starts it, at its position.
        player.togglePlayPause()
        awaitPlaying("a-book")

        // --- And over to B again: its state is there too.
        web.seen.clear()
        queued.clear()
        finalPosition = 80.0
        assertTrue(signOut.switchProfile(b))
        awaitShownPaused("b-book")
        assertEquals("matthias", rmab.config.first()!!.username)
        assertEquals(listOf("Bibi"), searches.queries.first())
        assertEquals(setOf(Triple(b, "b-waiting", null)), queued.toSet())
        assertEquals("nobody asked to delete anything", emptyList<KeptAccount>(), kept.list.value)
    }

    @Test fun offline_theSwitchGoesOn_theChangesWait_andGoUpWithTheirOwnTokenLater(): Unit = runBlocking {
        signedIn()
        aListensAndDownloads()
        reachable.value = false
        kotlinx.coroutines.withTimeout(5_000) { api.connection.first { it !is io.wolftown.lauschig.connection.ConnectionState.Online } }

        assertTrue(signOut.switchProfile(b))

        assertEquals(emptyList<TwoUsersServer.Seen>(), web.seen.toList())
        assertEquals(b, store.account.first()!!.serverId)
        assertTrue(db.progressDao().get(a, "a-book")!!.dirty)
        assertEquals(45.0, db.progressDao().get(a, "a-book")!!.currentTimeSec, 0.0)
        assertEquals(1, db.sessionDao().unsynced(a).size)
        assertEquals(listOf(BookmarkPending.UPSERT), db.bookmarkDao().pending(a).map { it.pending })

        reachable.value = true
        web.keepOnline(api, keepAlive)
        assertTrue(signOut.switchProfile(a))
        web.keepOnline(api, keepAlive)
        web.seen.clear()
        progressSync.sync(a)
        bookmarkSync.sync(a)
        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertTrue(web.seen.single { it.method == "PATCH" }.body.contains("\"currentTime\":45.0"))
        assertFalse(db.progressDao().get(a, "a-book")!!.dirty)
        assertEquals(0, db.sessionDao().unsynced(a).size)
        assertEquals(0, db.bookmarkDao().pending(a).size)
    }

    @Test fun addingAProfile_setsTheActiveOneAside_like_aSwitch(): Unit = runBlocking {
        store.save(web.a, TOKEN_A, "a-refresh")
        web.keepOnline(api, keepAlive)
        aListensAndDownloads()

        signOut.addProfile(web.b, TOKEN_B, "b-refresh")

        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertEquals(b, store.account.first()!!.serverId)
        assertEquals(TOKEN_A, store.savedCredentials(a)!!.accessToken)
        assertEquals(null, player.state.value.itemId)
        assertEquals(DownloadState.PAUSED, state(a, "a-running"))
        assertEquals(listOf("Die drei"), run { store.activate(a); searches.queries.first().also { store.activate(b) } })
        web.seen.clear()
        web.keepOnline(api, keepAlive)
        progressSync.sync(b)
        assertEquals(listOf<String?>(BEARER_B), web.tokens())
    }

    /**
     * Review focus 1, what the screens are told through a real switch ([AppViewModel], the start router and the profile
     * screens on the real flow): which pages, the running display and its result, no mini player and no question of A; and
     * on the way back A's title waits paused.
     */
    @Test fun whatTheScreensSee_throughARealSwitch_andBack(): Unit = runBlocking {
        signedIn()
        aListensAndDownloads()
        prompts.show(DownloadPrompt.Metered(a, "a-book", "Titel", 1))
        val viewModels = TestViewModels()
        try {
            val operations = ProfileOperations(signOut, scope)
            val main = viewModels.own(MainViewModel(store))
            val profiles = viewModels.own(ProfilesViewModel(store, operations, io.wolftown.lauschig.ui.profiles.ProfileGate(FakeProfileLocks()) { true }) { RemovalFacts(0, 0, false) })
            val starter = PlayDownloadStarter(flowOf(true), flowOf(MeteredDownload.ASK), {}, { false }, { _, _ -> null }, { _, _ -> null }, manager, prompts, scope)
            val frame = viewModels.own(AppViewModel(api, store, jumps, player, prompts, starter))
            suspend fun <T> soon(what: suspend () -> T): T = kotlinx.coroutines.withTimeout(10_000) { what() }
            val row = { id: String -> profiles.profiles.value!!.single { it.serverId == id } }
            soon { main.pages.first { it?.owner == a } }
            soon { profiles.profiles.first { it?.size == 2 } }
            soon { frame.downloadPrompt.first { it != null } }

            // The gate reads its store first; whoever looks from there on sees it running.
            val running = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { profiles.busy.first { it != null } }
            profiles.switchTo(row(b))
            assertEquals(ProfileWork.Switching(web.b.username), soon { running.await() })
            val done = soon { profiles.outcome.first { it != null } }!!
            assertEquals(ProfileOutcome.Kind.SWITCHED, done.kind)
            assertEquals(web.b.username, done.name)
            assertNull(profiles.busy.value)

            // New pages on the start tab, the player closed, no mini player and no question of A.
            soon { main.pages.first { it?.owner == b } }
            assertEquals(Start, main.pages.value!!.start)
            assertEquals(null, player.state.value.itemId)
            assertEquals(null, frame.downloadPrompt.value)
            assertTrue(row(b).active)
            assertEquals(true, soon { frame.serverId.first { it == b } } == b)
            operations.shown(done)

            profiles.switchTo(row(a))
            val back = soon { profiles.outcome.first { it != null } }!!
            assertEquals(ProfileOutcome.Kind.SWITCHED, back.kind)
            soon { main.pages.first { it?.owner == a } }
            awaitShownPaused("a-book")
        } finally {
            viewModels.clear()
        }
    }

    @Test fun anOpenDownloadQuestion_goesWithItsAccount_anotherAccountsStays(): Unit = runBlocking {
        signedIn()
        prompts.show(DownloadPrompt.Metered(a, "a-book", "Titel", 1))
        assertTrue(signOut.switchProfile(b))
        assertNull(prompts.prompt.value)

        // One about A that lands while B is active (not shown to B, see AppViewModelTest) stays for A's return.
        val late = DownloadPrompt.Metered(a, "a-book", "Titel", 1)
        prompts.show(late)
        assertTrue(signOut.switchProfile(a))
        assertEquals(late, prompts.prompt.value)
    }

    // Review I1: a title started in the gap between the first stop and the account's change.

    private suspend fun rowsToPlay() {
        librarySync.syncAll(a)
        librarySync.loadDetails(a, "a-book")
        db.itemDao().upsertDetails(ItemEntity(b, "b-book", "b-lib", "Title b", durationSec = 100.0))
        lastPlayed.set(LastPlayed(b, "b-book", 1_000))
        web.seen.clear()
    }

    private fun aStartsInTheGap() {
        whileCancelling = { sid ->
            if (sid == a) {
                player.play(a, "a-book")
                awaitPlaying("a-book")
            }
        }
    }

    @Test fun aTitleStartedWhileTheAccountIsLeft_doesNotSurviveTheSwitch(): Unit = runBlocking {
        signedIn()
        rowsToPlay()
        aStartsInTheGap()

        assertTrue(signOut.switchProfile(b))

        assertEquals(emptyList<String>(), facade.items.map { it.mediaId })
        assertFalse(facade.snap.isPlaying)
        // FakeFacade sends no HTTP: this is about what the controller loads for the title. The stream itself: StreamCallsTest.
        assertFalse("A's title's details are not asked for with B's token", web.seen.any { it.auth == BEARER_B && it.path.contains("a-book") })
        // B starts quietly, with its own title in the mini player, not A's.
        awaitShownPaused("b-book")
        assertEquals("b-book", starter.last()!!.itemId)
    }

    @Test fun aTitleStartedWhileTheAccountSignsOut_doesNotSurviveIt(): Unit = runBlocking {
        signedIn()
        rowsToPlay()
        aStartsInTheGap()

        signOut.execute(keepDownloads = true)

        assertNull(store.account.first())
        assertEquals(emptyList<String>(), facade.items.map { it.mediaId })
        assertEquals(null, player.state.value.itemId)
    }

    // Review I2: B's last title through a device title A heard afterwards.

    @Test fun aDeviceTitleAHeard_leavesBsLastTitleToB_andIsAsOnItsReturn(): Unit = runBlocking {
        signedIn()
        rowsToPlay()
        val dev = DeviceLibrary.SERVER_ID
        val local = "${DeviceLibrary.ID_PREFIX}0123456789abcdef01234567"
        db.itemDao().upsertDetails(ItemEntity(dev, local, "dev-lib", "Vom Gerät", durationSec = 100.0))
        lastPlayed.set(LastPlayed(dev, local, 2_000), account = a) // newer than B's title

        assertTrue(signOut.switchProfile(b))
        awaitShownPaused("b-book")
        assertEquals("the play key, the car, the widget", "b-book", starter.last()!!.itemId)

        assertTrue(signOut.switchProfile(a))
        awaitShownPaused(local)
        assertEquals(local, starter.last()!!.itemId)
    }

    /** B on another server: its token never reaches A's server, and removing it logs it out over its own addresses. */
    @Test fun aProfileOnAnotherServer_keepsItsTokensToItsServer_andIsLoggedOutThereWhenRemoved(): Unit = runBlocking {
        val second = TwoUsersServer()
        try {
            signedIn(second.b)
            librarySync.syncAll(a)
            web.seen.clear()
            assertTrue(signOut.switchProfile(b))
            web.keepOnline(api, keepAlive)
            kotlinx.coroutines.withTimeout(5_000) {
                api.connection.first { (it as? io.wolftown.lauschig.connection.ConnectionState.Online)?.baseUrl == second.server.url("/") }
            }
            assertEquals(ListenableWorker.Result.success(), syncWorker())
            assertEquals(listOf<String?>(BEARER_B), second.tokens())
            assertFalse("B's token never reached A's server", web.seen.any { it.auth == BEARER_B })
            assertNotNull(db.itemDao().get(b, "b-book"))

            // Back on A, its token only to its own server.
            assertTrue(signOut.switchProfile(a))
            kotlinx.coroutines.withTimeout(5_000) {
                api.connection.first { (it as? io.wolftown.lauschig.connection.ConnectionState.Online)?.baseUrl == web.server.url("/") }
            }
            second.seen.clear()
            web.seen.clear()
            rmabOf(b, "matthias")
            lastPlayed.set(LastPlayed(b, "b-book", 1))
            progressSync.sync(a)
            assertEquals(emptyList<TwoUsersServer.Seen>(), second.seen.toList())

            // The removal: the logout to B's server, its refresh token, no credentials; its data goes, A's stays.
            val coversBefore = diskCovers
            assertTrue(signOut.removeProfile(b))
            assertEquals("its covers go, like at a sign-out; A fetches its own again", coversBefore + 1, diskCovers)
            val logout = second.seen.single { it.path == "/logout" }
            assertEquals("b-refresh", logout.refreshToken)
            assertNull(logout.auth)
            assertFalse(web.seen.any { it.path == "/logout" })
            assertNull(store.savedCredentials(b))
            assertNull(db.itemDao().get(b, "b-book"))
            assertNull(lastPlayed.get(b))
            assertEquals(a, store.account.first()!!.serverId)
            assertNotNull(db.itemDao().get(a, "a-book"))
            store.save(web.a, TOKEN_A, "a-refresh") // nothing of the removed account's connection is left to find
            assertNull(rmabStore.data.first()[stringPreferencesKey("token:$b")])
        } finally {
            second.shutdown()
        }
    }

    @Test fun aRemovedProfilesServerThatDoesNotAnswer_doesNotStopTheRemoval(): Unit = runBlocking {
        val gone = TwoUsersServer()
        val account = gone.b
        gone.shutdown()
        signedIn(account)
        assertTrue(signOut.removeProfile(b))
        assertNull(store.savedCredentials(b))
        assertEquals(a, store.account.first()!!.serverId)
    }

    /** The values 0.4.0 left behind belong to the account active at the upgrade, and a switch keeps them with it. */
    @Test fun upgrade_theOldValuesStayWithTheAccountActiveAtTheUpgrade(): Unit = runBlocking {
        val old = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = old) { prefsFile("last_played") }.edit {
            it[stringPreferencesKey("server_id")] = a
            it[stringPreferencesKey("item_id")] = "a-book"
            it[longPreferencesKey("paused_at")] = 9_000
        }
        old.cancel()
        old.coroutineContext[kotlinx.coroutines.Job]!!.join()
        rmabStore.edit {
            it[stringPreferencesKey("url")] = "https://rmab.example/"
            it[stringPreferencesKey("username")] = "fabi"
            it[stringPreferencesKey("token")] = "enc:rmab_old"
        }
        searchesStore.edit { it[stringPreferencesKey("queries")] = "Die drei" }
        signedIn()
        librarySync.syncAll(a)

        cleaner.migrate(store.account.first()?.serverId) // what the app does at every start
        assertEquals("fabi", rmab.config.first()!!.username)
        assertEquals(listOf("Die drei"), searches.queries.first())
        assertEquals("a-book", starter.last()!!.itemId)

        assertTrue(signOut.switchProfile(b))
        assertNull(rmab.config.first())
        assertEquals(emptyList<String>(), searches.queries.first())
        assertNull(starter.last())
        assertEquals("a-book", lastPlayed.forAccount(a)!!.itemId)

        assertTrue(signOut.switchProfile(a))
        assertEquals("fabi", rmab.config.first()!!.username)
        assertEquals(listOf("Die drei"), searches.queries.first())
        awaitShownPaused("a-book")
    }

    // Review N3: the device's own entry.

    private val localTitle = "${DeviceLibrary.ID_PREFIX}0123456789abcdef01234567"

    private suspend fun deviceTitleRow() = db.itemDao().upsertDetails(ItemEntity(DeviceLibrary.SERVER_ID, localTitle, "dev-lib", "Vom Gerät", durationSec = 100.0))

    @Test fun upgrade_anOldDeviceTitleIsTheActiveAccounts_notTheNextOnes(): Unit = runBlocking {
        val old = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = old) { prefsFile("last_played") }.edit {
            it[stringPreferencesKey("server_id")] = DeviceLibrary.SERVER_ID
            it[stringPreferencesKey("item_id")] = localTitle
            it[longPreferencesKey("paused_at")] = 9_000
        }
        old.cancel()
        old.coroutineContext[kotlinx.coroutines.Job]!!.join()
        signedIn()
        deviceTitleRow()

        cleaner.migrate(store.account.first()?.serverId) // what the app does at every start
        assertEquals(localTitle, starter.last()!!.itemId)

        assertTrue(signOut.switchProfile(b))
        assertNull("B has played nothing and gets nothing", starter.last())

        assertTrue(signOut.switchProfile(a))
        awaitShownPaused(localTitle)
        assertEquals(localTitle, starter.last()!!.itemId)
    }

    @Test fun aProfileRemovedWhileAnotherIsActive_leavesItNoDeviceTitle(): Unit = runBlocking {
        signedIn()
        deviceTitleRow()
        lastPlayed.set(LastPlayed(DeviceLibrary.SERVER_ID, localTitle, 2_000), account = b)
        assertTrue(signOut.removeProfile(b))
        assertNull(starter.last())
    }

    /** One account, as today: a sign-out takes what is its own, and only that. */
    @Test fun signingOutWithAProfileSetAside_takesOnlyTheActiveAccountsState(): Unit = runBlocking {
        signedIn()
        librarySync.syncAll(a)
        rmabOf(a, "fabi")
        rmabOf(b, "matthias")
        searches.record("Die drei")
        lastPlayed.set(LastPlayed(a, "a-book", 1))
        lastPlayed.set(LastPlayed(b, "b-book", 2))
        signOut.execute(keepDownloads = false)

        assertNull(store.account.first())
        assertEquals("the saved one is still there", b, store.saved.first().single().serverId)
        assertEquals(1, diskCovers)
        assertTrue(web.seen.any { it.path == "/logout" && it.refreshToken == "a-refresh" })
        assertNull(rmabStore.data.first()[stringPreferencesKey("token:$a")])
        assertEquals("enc:rmab_matthias", rmabStore.data.first()[stringPreferencesKey("token:$b")])
        assertNull(lastPlayed.get(a))
        assertEquals("b-book", lastPlayed.get(b)!!.itemId)
        assertNull(db.itemDao().get(a, "a-book"))
    }

    // --- Task 3: the protection through the real sign-out flow ---

    @Test fun aSwitch_keepsTheProtectionOfBoth_theWayBackAsksAgain(): Unit = runBlocking {
        signedIn()
        locks.setPin(a, "1111")
        locks.setPin(b, "2222")
        assertTrue(signOut.switchProfile(b))
        assertEquals(ProfileLockKind.PIN, locks.kind(a))
        assertEquals(ProfileLockKind.PIN, locks.kind(b))
        assertEquals(PinCheck.Correct, locks.verifyPin(a, "1111"))
    }

    @Test fun aRemovedProfile_takesItsProtection_andASignInStartsWithout(): Unit = runBlocking {
        signedIn()
        locks.setPin(a, "1111")
        locks.setPin(b, "2222")
        assertTrue(signOut.removeProfile(b))
        assertEquals(ProfileLockKind.NONE, locks.kind(b))
        assertEquals("not the other's", ProfileLockKind.PIN, locks.kind(a))
        signOut.addProfile(web.b, TOKEN_B, "b-refresh")
        assertEquals(ProfileLockKind.NONE, locks.kind(b))
    }

    @Test fun aSignOut_takesTheProtection_alsoWhenTheDownloadsStay(): Unit = runBlocking {
        for (keep in listOf(true, false)) {
            signedIn()
            locks.setPin(a, "1111")
            locks.setPin(b, "2222")
            signOut.execute(keepDownloads = keep)
            assertEquals(ProfileLockKind.NONE, locks.kind(a))
            assertEquals(ProfileLockKind.PIN, locks.kind(b))
        }
    }

    /** Review focus 3, on the real flow: before the gate nothing of the account stops; after it the switch goes through. */
    @Test fun theGate_comesBeforeAnythingOfTheSwitch(): Unit = runBlocking {
        signedIn()
        locks.setPin(b, "2222")
        val viewModels = TestViewModels()
        try {
            val profiles = viewModels.own(ProfilesViewModel(store, ProfileOperations(signOut, scope), ProfileGate(locks) { true }) { RemovalFacts(0, 0, false) })
            suspend fun <T> soon(what: suspend () -> T): T = kotlinx.coroutines.withTimeout(10_000) { what() }
            val row = soon { profiles.profiles.first { it?.size == 2 } }!!.single { it.serverId == b }
            cancelled.clear()
            profiles.switchTo(row)
            soon { profiles.gatePrompt.first { it is GatePrompt.Pin } }
            assertEquals(a, store.account.first()!!.serverId)
            assertTrue("no work of A was cancelled", cancelled.isEmpty())
            assertEquals(0, memoryCovers)

            profiles.submitPin("2222")
            assertEquals(ProfileOutcome.Kind.SWITCHED, soon { profiles.outcome.first { it != null } }!!.kind)
            assertEquals(b, store.account.first()!!.serverId)
            assertEquals(listOf(a), cancelled)
        } finally {
            viewModels.clear()
        }
    }

    /**
     * Review focus 3, the sign-in page: the password is the stronger proof (3.3), so it comes in without the gate. Only the way of a
     * forgotten PIN takes the protection off; with a plain sign-in it stays, for the way back.
     */
    @Test fun aSignInOfAProtectedProfile_needsItsPassword_andTheProtectionStays(): Unit = runBlocking {
        signedIn()
        locks.setPin(b, "2222")
        signOut.addProfile(web.b, "fresh-access", "fresh-refresh")
        assertEquals(b, store.account.first()!!.serverId)
        assertEquals(ProfileLockKind.PIN, locks.kind(b))
        assertEquals(PinCheck.Correct, locks.verifyPin(b, "2222"))
        signOut.signIn(web.b, "fresher-access", "fresher-refresh")
        assertEquals("a re-auth of the active account", ProfileLockKind.PIN, locks.kind(b))
    }

    /** 3.6 on the real flow: the signed-in account comes in with its new tokens, the protection goes, the one before stays signed in. */
    @Test fun aForgottenPin_comesInAndTakesTheProtectionOff_theOtherStays(): Unit = runBlocking {
        signedIn()
        locks.setPin(a, "1111")
        locks.setPin(b, "2222")
        val operations = ProfileOperations(signOut, scope, locks::remove)
        operations.unlock(web.b, "fresh-access", "fresh-refresh")
        assertEquals(b, store.account.first()!!.serverId)
        assertEquals("fresh-access", store.accessToken())
        assertEquals(ProfileLockKind.NONE, locks.kind(b))
        assertEquals(a, store.saved.first().single().serverId)
        assertEquals("the one left keeps its own", ProfileLockKind.PIN, locks.kind(a))
        assertEquals(ProfileOutcome.Kind.UNLOCKED, operations.outcome.value!!.kind)
    }

    private companion object {
        /** When the tracker's save lands after the pause. */
        const val TRACKER_SAVE_MS = 150L
    }
}
