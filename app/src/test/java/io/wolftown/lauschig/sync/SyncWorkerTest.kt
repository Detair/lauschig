package io.wolftown.lauschig.sync

import io.wolftown.lauschig.StubLibraryApi
import io.wolftown.lauschig.StubMeApi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.settings.AutoDownloadMode
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private lateinit var db: LauschigDatabase
    private val calls = AtomicInteger()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private val lib = object : StubLibraryApi() {
        override suspend fun libraries(): LibrariesResponse { calls.incrementAndGet(); return LibrariesResponse(emptyList()) }
        override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int) = LibraryItemsPage(emptyList(), 0, limit, page)
        override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse = error("unused")
        override suspend fun item(id: String, expanded: Int): LibraryItemDto = error("unused")
        override suspend fun authors(id: String): io.wolftown.lauschig.absapi.library.AuthorsResponse = throw UnsupportedOperationException()
        override suspend fun playlists() = io.wolftown.lauschig.absapi.library.PlaylistsResponse(emptyList())
    }
    private val me = object : StubMeApi() {
        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun bookmarks() = io.wolftown.lauschig.absapi.me.BookmarksResponse(emptyList())
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) {}
        override suspend fun syncLocalSessions(body: LocalSessionsBody) = LocalSessionsResult(emptyList())
    }

    private val noDownloads = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: androidx.work.NetworkType?) {}
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): kotlinx.coroutines.flow.Flow<DownloadProgress?> = flowOf(null)
    }

    private val order = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val listeningAsked = AtomicInteger()
    private var listeningFails = false
    private val listeningApi = object : StubMeApi() {
        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("unused")
        override suspend fun syncLocalSessions(body: LocalSessionsBody) = error("unused")
        override suspend fun listeningSessions(itemsPerPage: Int, page: Int): io.wolftown.lauschig.absapi.me.UserListeningSessionsPage {
            listeningAsked.incrementAndGet()
            order += "listening"
            if (listeningFails) throw java.io.IOException("down")
            return io.wolftown.lauschig.absapi.me.UserListeningSessionsPage()
        }
    }

    private suspend fun runWorker(gate: OnlineGate): ListenableWorker.Result {
        val store = FakeAccountStore()
        store.acc.value = Account("aaaaaaaaaaaaaaaa", "u", "n", false, ServerAddresses(null, null), emptySet())
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = SyncWorker(
                c, p, store,
                LibrarySync({ flowOf(lib) }, db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { it() }),
                ProgressSync({ flowOf(me) }, db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto() }),
                PositionJumpBus(), gate,
                EpisodeAutoDownloader(kotlinx.coroutines.flow.flow { order += "autoDownload"; emit(AutoDownloadMode.OFF) }, db.episodeDao(), db.progressDao(), db.downloadDao(), noDownloads, { emptySet() }, {}),
                BookmarkSync({ flowOf(me) }, db.bookmarkDao()),
                CollectionSync({ flowOf(lib) }, db.libraryDao(), db.collectionDao(), db.episodeDao(), db.audioFileDao()) { it() },
                ListeningSessionSync({ flowOf(listeningApi) }, db.listeningSessionDao(), db.sessionDao(), InMemorySyncStamps(), { true }),
            )
        }
        return TestListenableWorkerBuilder<SyncWorker>(ctx).setWorkerFactory(factory).build().doWork()
    }

    @Test fun coldStart_waitsForOnline_thenSyncs() = runBlocking {
        val online = MutableStateFlow(false)
        val gate = OnlineGate { withTimeoutOrNull(2_000.milliseconds) { online.first { it } } != null }
        val job = launch { delay(100.milliseconds); online.value = true }
        val r = runWorker(gate)
        job.join()
        assertEquals(ListenableWorker.Result.success(), r)
        assertEquals(1, calls.get())
    }

    @Test fun listeningSessions_areFetched_andAFailureNeverFailsTheWorker() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), runWorker { true })
        assertEquals(1, listeningAsked.get())
        listeningFails = true
        assertEquals(ListenableWorker.Result.success(), runWorker { true })
        assertEquals(2, listeningAsked.get())
    }

    @Test fun listeningSessions_comeAfterTheAutoDownloader() = runBlocking {
        // A long first run of the statistics must not hold back (or, cancelled, skip) new podcast episodes.
        assertEquals(ListenableWorker.Result.success(), runWorker { true })
        assertEquals(listOf("autoDownload", "listening"), order.filter { it == "autoDownload" || it == "listening" })
    }

    @Test fun staysOffline_retries() = runBlocking {
        val r = runWorker { false }
        assertEquals(ListenableWorker.Result.retry(), r)
        assertEquals(0, calls.get())
    }
}
