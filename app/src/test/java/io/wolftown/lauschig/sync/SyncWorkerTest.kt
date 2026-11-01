package io.wolftown.lauschig.sync

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

    private val lib = object : LibraryApi {
        override suspend fun libraries(): LibrariesResponse { calls.incrementAndGet(); return LibrariesResponse(emptyList()) }
        override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int) = LibraryItemsPage(emptyList(), 0, limit, page)
        override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse = error("unused")
        override suspend fun item(id: String, expanded: Int): LibraryItemDto = error("unused")
        override suspend fun authors(id: String): io.wolftown.lauschig.absapi.library.AuthorsResponse = throw UnsupportedOperationException()
    }
    private val me = object : MeApi {
        override suspend fun me() = AbsUser("u", "n", "user")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) {}
        override suspend fun syncLocalSessions(body: LocalSessionsBody) = LocalSessionsResult(emptyList())
    }

    private suspend fun runWorker(gate: OnlineGate): ListenableWorker.Result {
        val store = FakeAccountStore()
        store.acc.value = Account("aaaaaaaaaaaaaaaa", "u", "n", false, ServerAddresses(null, null), emptySet())
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = SyncWorker(
                c, p, store,
                LibrarySync(flowOf(lib), db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { it() }),
                ProgressSync(flowOf(me), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto() }),
                PositionJumpBus(), gate,
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

    @Test fun staysOffline_retries() = runBlocking {
        val r = runWorker { false }
        assertEquals(ListenableWorker.Result.retry(), r)
        assertEquals(0, calls.get())
    }
}
