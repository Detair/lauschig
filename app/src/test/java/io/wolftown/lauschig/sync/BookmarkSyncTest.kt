package io.wolftown.lauschig.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.StubMeApi
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.BookmarkBody
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.BookmarksResponse
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.BookmarkPending
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody(null)))

/** In-memory ABS bookmark store with the server's rules (create is idempotent, update/delete 404 when missing). */
private class BookmarkServer : StubMeApi() {
    val rows = mutableMapOf<Pair<String, Double>, BookmarkDto>()
    val calls = mutableListOf<String>()
    var goneItems = setOf<String>()
    var noBookmarksEndpoint = false
    var failAll = false

    override suspend fun me() = AbsUser("u", "n", "user", bookmarks = rows.values.toList()).also { calls += "me" }
    override suspend fun bookmarks(): BookmarksResponse {
        calls += "list"
        if (failAll) throw IOException("down")
        if (noBookmarksEndpoint) throw http(404)
        return BookmarksResponse(rows.values.toList())
    }
    override suspend fun createBookmark(itemId: String, body: BookmarkBody): BookmarkDto {
        calls += "POST $itemId ${body.time} ${body.title}"
        if (failAll) throw IOException("down")
        if (itemId in goneItems) throw http(404)
        return BookmarkDto(itemId, body.time, body.title, 1).also { rows[itemId to body.time] = it }
    }
    override suspend fun updateBookmark(itemId: String, body: BookmarkBody): BookmarkDto {
        calls += "PATCH $itemId ${body.time} ${body.title}"
        val key = itemId to body.time
        if (key !in rows) throw http(404)
        return rows.getValue(key).copy(title = body.title).also { rows[key] = it }
    }
    override suspend fun deleteBookmark(itemId: String, time: String) {
        calls += "DELETE $itemId $time"
        rows.remove(itemId to time.toDouble()) ?: throw http(404)
    }
    override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
    override suspend fun updateProgress(itemId: String, u: ProgressUpdate) = error("unused")
    override suspend fun syncLocalSessions(body: LocalSessionsBody): LocalSessionsResult = error("unused")
}

@RunWith(RobolectricTestRunner::class)
class BookmarkSyncTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"
    private val server = BookmarkServer()
    private val sync by lazy { BookmarkSync(flowOf(server), db.bookmarkDao()) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun newBookmark_isCreatedWithClockTitle_thenSynced() = runTest {
        db.bookmarkDao().save(s, "li_1", 123.0, null, now = 1)
        sync.sync(s)
        assertEquals("POST li_1 123.0 02:03", server.calls.first())
        val row = db.bookmarkDao().get(s, "li_1", 123.0)!!
        assertEquals(BookmarkPending.NONE, row.pending)
        assertTrue(row.onServer)
        assertNull(row.note)
    }

    @Test fun editOfPushedBookmark_patches_andRecreatesIfGoneElsewhere() = runTest {
        server.rows["li_1" to 10.0] = BookmarkDto("li_1", 10.0, "a", 1)
        sync.sync(s) // pull
        db.bookmarkDao().save(s, "li_1", 10.0, "b", now = 2)
        server.rows.clear() // deleted on another device meanwhile
        server.calls.clear()
        sync.sync(s)
        assertEquals(listOf("PATCH li_1 10.0 b", "POST li_1 10.0 b", "list"), server.calls)
        assertEquals("b", server.rows.getValue("li_1" to 10.0).title)
        assertEquals(BookmarkPending.NONE, db.bookmarkDao().get(s, "li_1", 10.0)!!.pending)
    }

    @Test fun delete_removesOnServer_404CountsAsDone() = runTest {
        server.rows["li_1" to 10.5] = BookmarkDto("li_1", 10.5, "a", 1)
        server.rows["li_1" to 20.0] = BookmarkDto("li_1", 20.0, "b", 1)
        sync.sync(s)
        db.bookmarkDao().delete(s, "li_1", 10.5)
        db.bookmarkDao().delete(s, "li_1", 20.0)
        server.rows.remove("li_1" to 20.0) // already gone
        sync.sync(s)
        assertTrue(server.calls.contains("DELETE li_1 10.5"))
        assertTrue(server.rows.isEmpty())
        assertTrue(db.bookmarkDao().all(s).isEmpty())
    }

    @Test fun goneItem_keepsItsOpPending_othersGoThrough() = runTest {
        server.goneItems = setOf("li_gone")
        db.bookmarkDao().save(s, "li_gone", 1.0, null, now = 1)
        db.bookmarkDao().save(s, "li_ok", 2.0, null, now = 1)
        sync.sync(s)
        assertEquals(BookmarkPending.UPSERT, db.bookmarkDao().get(s, "li_gone", 1.0)!!.pending)
        assertEquals(BookmarkPending.NONE, db.bookmarkDao().get(s, "li_ok", 2.0)!!.pending)
    }

    @Test fun episodeBookmarks_neverReachTheServer_andSurviveThePull() = runTest {
        db.bookmarkDao().save(s, "pod~ep", 5.0, "x", now = 1)
        sync.sync(s)
        assertEquals(listOf("list"), server.calls)
        assertEquals("x", db.bookmarkDao().get(s, "pod~ep", 5.0)!!.note)
    }

    @Test fun olderServer_fallsBackToMe() = runTest {
        server.noBookmarksEndpoint = true
        server.rows["li_1" to 3.0] = BookmarkDto("li_1", 3.0, "Hi", 1)
        sync.sync(s)
        assertEquals(listOf("list", "me"), server.calls)
        assertEquals("Hi", db.bookmarkDao().get(s, "li_1", 3.0)!!.note)
    }

    @Test fun networkFailure_propagates_andKeepsOutbox() = runTest {
        server.failAll = true
        db.bookmarkDao().save(s, "li_1", 3.0, null, now = 1)
        val result = runCatching { sync.sync(s) }
        assertTrue(result.isFailure)
        assertEquals(BookmarkPending.UPSERT, db.bookmarkDao().get(s, "li_1", 3.0)!!.pending)
    }

    @Test fun offline_isNoop() = runTest {
        db.bookmarkDao().save(s, "li_1", 3.0, null, now = 1)
        BookmarkSync(flowOf(null), db.bookmarkDao()).sync(s)
        assertEquals(BookmarkPending.UPSERT, db.bookmarkDao().get(s, "li_1", 3.0)!!.pending)
    }
}
