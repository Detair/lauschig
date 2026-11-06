package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.wolftown.lauschig.StubMeApi
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.BookmarkBody
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.BookmarksResponse
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BookmarkSyncWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val calls = mutableListOf<String>()

    private val me = object : StubMeApi() {
        override suspend fun me(): AbsUser = error("a bookmark-only run never fetches /api/me")
        override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("no progress")
        override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("no progress")
        override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult = error("no sessions")
        val stored = mutableListOf<BookmarkDto>()
        override suspend fun bookmarks() = BookmarksResponse(stored.toList()).also { calls += "list" }
        override suspend fun createBookmark(itemId: String, body: BookmarkBody) = BookmarkDto(itemId, body.time, body.title).also { stored += it; calls += "POST" }
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun runWorker(gate: OnlineGate): ListenableWorker.Result {
        val store = FakeAccountStore()
        store.acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet())
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) =
                BookmarkSyncWorker(c, p, store, gate, BookmarkSync({ flowOf(me) }, db.bookmarkDao()))
        }
        return TestListenableWorkerBuilder<BookmarkSyncWorker>(ctx).setWorkerFactory(factory).build().doWork()
    }

    @Test fun pushesAndPullsBookmarksOnly() = runBlocking {
        db.bookmarkDao().save(s, "li_1", 5.0, null, now = 1)
        assertEquals(ListenableWorker.Result.success(), runWorker { true })
        assertEquals(listOf("POST", "list"), calls)
        assertEquals(BookmarkPending.NONE, db.bookmarkDao().get(s, "li_1", 5.0)!!.pending)
    }

    @Test fun offline_retriesLater() = runBlocking {
        assertEquals(ListenableWorker.Result.retry(), runWorker { false })
        assertEquals(emptyList<String>(), calls)
    }
}
