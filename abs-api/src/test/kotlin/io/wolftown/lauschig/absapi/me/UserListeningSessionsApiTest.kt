package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.AbsClientFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** `GET /api/me/listening-sessions`: every session of the user, paged (0-based), newest `updatedAt` first. */
class UserListeningSessionsApiTest {
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
    fun listeningSessions_pagesAndParses_toleratingStringNumbersAndBrokenRows() = runBlocking {
        // Shaped like ABS 2.37.1 (PlaybackSession.toOldJSON), trimmed; the server adds fields freely.
        server.enqueue(
            json(
                """{"total":3,"numPages":2,"page":0,"itemsPerPage":100,"sessions":[""" +
                    """{"id":"s1","userId":"u","libraryId":"lib","libraryItemId":"li_1","bookId":"b1","episodeId":null,"mediaType":"book",""" +
                    """"mediaMetadata":{"title":"Der Anfang","authors":[{"id":"a1","name":"Test Autor"}],"series":[]},"chapters":[],""" +
                    """"displayTitle":"Der Anfang","displayAuthor":"Test Autor, Zweite Autorin","coverPath":null,"duration":1200,"playMethod":3,""" +
                    """"mediaPlayer":"lauschig","deviceInfo":{"clientName":"Lauschig"},"serverVersion":"2.37.1","date":"2026-11-03","dayOfWeek":"Tuesday",""" +
                    """"timeListening":"57.5","startTime":0,"currentTime":"1200","startedAt":1793752200000,"updatedAt":1793752260000},""" +
                    """{"id":"s2","libraryItemId":"li_pod","episodeId":"ep_1","mediaType":"podcast","mediaMetadata":{"title":"Der Podcast","author":"Pod Autor"},""" +
                    """"displayTitle":"Folge 1","displayAuthor":"Pod Autor","duration":null,"timeListening":30,"startTime":5,"currentTime":35,""" +
                    """"startedAt":1793752000000,"updatedAt":1793752030000},""" +
                    """{"id":"broken","startedAt":"gestern"}]}""",
            ),
        )

        val page = api.listeningSessions(itemsPerPage = 100, page = 0)

        assertEquals("/api/me/listening-sessions?itemsPerPage=100&page=0", server.takeRequest().path)
        assertEquals(3, page.total)
        assertEquals(2, page.numPages)
        assertEquals(2, page.sessions.size)
        val book = page.sessions[0]
        assertEquals("s1", book.id)
        assertEquals("li_1", book.libraryItemId)
        assertNull(book.episodeId)
        assertEquals("book", book.mediaType)
        assertEquals("Der Anfang", book.displayTitle)
        assertEquals("Test Autor, Zweite Autorin", book.displayAuthor)
        assertEquals(1200.0, book.duration, 0.0)
        assertEquals(57.5, book.timeListening, 0.0)
        assertEquals(1200.0, book.currentTime, 0.0)
        assertEquals(1793752200000L, book.startedAt)
        assertEquals(1793752260000L, book.updatedAt)
        val episode = page.sessions[1]
        assertEquals("ep_1", episode.episodeId)
        assertEquals("podcast", episode.mediaType)
        assertEquals("Folge 1", episode.displayTitle)
        assertEquals("Der Podcast", episode.mediaMetadata?.title)
        assertEquals(0.0, episode.duration, 0.0) // null is 0
        assertEquals(5.0, episode.startTime, 0.0)
    }

    @Test
    fun listeningSessions_laterPage_andMissingFields() = runBlocking {
        server.enqueue(json("""{"total":101,"numPages":2,"page":1,"itemsPerPage":100,"sessions":[{"id":"s","startedAt":1,"updatedAt":2}]}"""))
        server.enqueue(json("""{}"""))

        val page = api.listeningSessions(itemsPerPage = 100, page = 1)
        val empty = api.listeningSessions(itemsPerPage = 25, page = 0)

        assertEquals("/api/me/listening-sessions?itemsPerPage=100&page=1", server.takeRequest().path)
        assertEquals("/api/me/listening-sessions?itemsPerPage=25&page=0", server.takeRequest().path)
        val s = page.sessions.single()
        assertNull(s.libraryItemId)
        assertNull(s.mediaType)
        assertNull(s.displayTitle)
        assertNull(s.mediaMetadata)
        assertEquals(0.0, s.timeListening, 0.0)
        assertEquals(emptyList<UserListeningSessionDto>(), empty.sessions)
        assertEquals(0, empty.numPages)
    }
}
