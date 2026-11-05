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
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.OkHttpReachabilityProbe
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
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
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.TwoUsersServer
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_A
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
import io.wolftown.lauschig.sync.SyncWorker
import io.wolftown.lauschig.ui.search.RecentSearches
import io.wolftown.lauschig.ui.setup.OidcLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
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
 * encrypted tokens, the provider and its clients, Room, the syncs, the download worker, the sign-out flow, the player
 * controller and every per-account store. Two users on one [TwoUsersServer]: A plays and downloads, the switch to B, a late
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
            clearMemoryCovers = { memoryCovers++ }, clearMoments = { jumps.clear() },
        )
    }

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

    private val signOut by lazy {
        SignOut(
            accounts = store,
            data = DeviceAccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), files, stamps, {}),
            kept = kept,
            work = object : AccountWork {
                override suspend fun cancel(serverId: String) { cancelled += serverId }
                override suspend fun cancelOwn(serverId: String) { cancelled += "own:$serverId" }
            },
            lastPlayed = lastPlayed,
            pausePlayback = {
                val s = player.state.value
                if (s.isPlaying) {
                    player.pause()
                    val sid = store.account.first()!!.serverId
                    db.progressDao().saveLocal(sid, s.itemId!!, finalPosition, 100.0, false, ++clock)
                    db.sessionDao().upsert(LocalSessionEntity("session-$sid-$clock", sid, s.itemId!!, 0.0, finalPosition, finalPosition, 1_000, clock))
                }
            },
            stopPlayback = { player.stop() },
            pushes = listOf({ sid -> progressSync.sync(sid) }, { sid -> bookmarkSync.sync(sid) }),
            awaitOnline = { awaitConnectionAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS) },
            logout = serverLogout::logout,
            clearAccountState = cleaner::clear,
            setAsideState = cleaner::setAside,
            forgetSavedState = cleaner::forget,
            noteDownloads = switched::rememberActive,
            resumeDownloads = switched::resume,
            forgetDownloads = switched::forget,
            syncNow = { syncs++ },
            showLastTitle = { sid -> starter.last()?.takeIf { it.serverId == sid }?.let { player.showPaused(it.serverId, it.itemId) } },
            logoutProfile = serverLogout::logoutProfile,
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
        assertEquals(1, facade.stops)
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
        // And an open question about A's download, landing late, is cleared at the way back at the latest (AppViewModelTest: not shown).
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

    @Test fun anOpenDownloadQuestion_goesAtTheSwitch_anotherAccountsStays(): Unit = runBlocking {
        signedIn()
        prompts.show(DownloadPrompt.Metered(a, "a-book", "Titel", 1))
        assertTrue(signOut.switchProfile(b))
        assertNull(prompts.prompt.value)
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
            assertTrue(signOut.removeProfile(b))
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
}
