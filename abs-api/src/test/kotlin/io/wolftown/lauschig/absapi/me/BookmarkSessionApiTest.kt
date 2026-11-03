package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.AbsClientFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BookmarkSessionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MeApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(MeApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun bookmarks_parsesList_andDropsBrokenRows() = runBlocking {
        server.enqueue(
            json(
                """{"bookmarks":[{"libraryItemId":"li_1","time":123,"title":"Gut","createdAt":1700},""" +
                    """{"libraryItemId":"li_2","time":5.5,"title":"x","createdAt":1800},{"time":"bad"}]}""",
            ),
        )

        val list = api.bookmarks().bookmarks!!

        assertEquals("/api/me/bookmarks", server.takeRequest().path)
        assertEquals(2, list.size)
        assertEquals(BookmarkDto("li_1", 123.0, "Gut", 1700), list[0])
        assertEquals(5.5, list[1].time, 0.0)
    }

    @Test
    fun me_carriesBookmarks() = runBlocking {
        server.enqueue(json("""{"id":"u","username":"a","type":"user","bookmarks":[{"libraryItemId":"li_1","time":1,"title":"t","createdAt":2}]}"""))

        val me = api.me()

        assertEquals(listOf(BookmarkDto("li_1", 1.0, "t", 2)), me.bookmarks)
    }

    @Test
    fun missingBookmarksField_isNull_notEmpty() = runBlocking {
        server.enqueue(json("""{"id":"u","username":"a","type":"user"}"""))
        server.enqueue(json("""{}"""))

        assertNull(api.me().bookmarks)
        assertNull(api.bookmarks().bookmarks)
    }

    @Test
    fun createBookmark_postsTimeAndTitle() = runBlocking {
        server.enqueue(json("""{"libraryItemId":"li_1","time":60,"title":"Hier","createdAt":99}"""))

        val created = api.createBookmark("li_1", BookmarkBody(60.0, "Hier"))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/me/item/li_1/bookmark", req.path)
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(60.0, sent.getValue("time").jsonPrimitive.double, 0.0)
        assertEquals("Hier", sent.getValue("title").jsonPrimitive.content)
        assertEquals(99L, created.createdAt)
    }

    @Test
    fun updateBookmark_patches() = runBlocking {
        server.enqueue(json("""{"libraryItemId":"li_1","time":60,"title":"Neu","createdAt":99}"""))

        api.updateBookmark("li_1", BookmarkBody(60.0, "Neu"))

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/item/li_1/bookmark", req.path)
    }

    @Test
    fun deleteBookmark_putsExactTimeInPath() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))

        api.deleteBookmark("li_1", bookmarkTimePath(123.0))
        api.deleteBookmark("li_1", bookmarkTimePath(123.5))

        val first = server.takeRequest()
        assertEquals("DELETE", first.method)
        assertEquals("/api/me/item/li_1/bookmark/123", first.path)
        assertEquals("/api/me/item/li_1/bookmark/123.5", server.takeRequest().path)
    }

    @Test
    fun bookmarkTimePath_isPlainDecimal() {
        assertEquals("0", bookmarkTimePath(0.0))
        assertEquals("86400", bookmarkTimePath(86400.0))
        assertEquals("12345678", bookmarkTimePath(12345678.0))
        assertEquals("0.25", bookmarkTimePath(0.25))
    }

    @Test
    fun itemListeningSessions_book_parsesSessions_toleratingStringTimes() = runBlocking {
        server.enqueue(
            json(
                """{"total":2,"numPages":1,"page":0,"itemsPerPage":50,"sessions":[""" +
                    """{"id":"s1","libraryItemId":"li_1","episodeId":null,"displayTitle":"B","duration":100.5,"timeListening":"42",""" +
                    """"startTime":10.0,"currentTime":52.0,"startedAt":1000,"updatedAt":2000,"deviceInfo":{"deviceName":"Pixel 9","clientName":"Lauschig"}},""" +
                    """{"id":"s2","libraryItemId":"li_1","timeListening":7,"startTime":0,"currentTime":7,"startedAt":500,"updatedAt":600,"deviceInfo":null},""" +
                    """{"id":"broken","startedAt":"x"}]}""",
            ),
        )

        val page = api.itemListeningSessions("li_1", itemsPerPage = 50)

        assertEquals("/api/me/item/listening-sessions/li_1?itemsPerPage=50&page=0", server.takeRequest().path)
        assertEquals(2, page.sessions.size)
        val s1 = page.sessions[0]
        assertEquals(42.0, s1.timeListening, 0.0)
        assertEquals(52.0, s1.currentTime, 0.0)
        assertEquals("Pixel 9", s1.deviceInfo?.deviceName)
        assertNull(page.sessions[1].deviceInfo)
        assertEquals(2, page.total)
    }

    @Test
    fun itemListeningSessions_episode_usesEpisodePath() = runBlocking {
        server.enqueue(json("""{"total":0,"numPages":0,"page":0,"itemsPerPage":10,"sessions":[]}"""))

        api.episodeListeningSessions("li_pod", "ep_1", itemsPerPage = 10)

        assertEquals("/api/me/item/listening-sessions/li_pod/ep_1?itemsPerPage=10&page=0", server.takeRequest().path)
    }
}
