package io.wolftown.lauschig.absapi.library

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

class CollectionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LibraryApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(LibraryApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    // Expanded library items as the server sends them; only the id matters to us.
    private fun book(id: String) =
        """{"id":"$id","libraryId":"lib1","mediaType":"book","updatedAt":1,"media":{"metadata":{"title":"T $id"},"audioFiles":[{"index":1,"ino":"9","duration":1.0,"metadata":{"filename":"a.mp3","ext":".mp3","size":1}}]}}"""

    @Test
    fun collections_keepsBookOrder_andDropsBrokenRows() = runBlocking {
        server.enqueue(
            json(
                """{"results":[{"id":"col1","libraryId":"lib1","name":"Gute Nacht","description":null,"lastUpdate":77,"createdAt":1,""" +
                    """"books":[${book("b2")},${book("b1")},{"nope":1}]},{"name":"broken"}],"total":2,"limit":0,"page":0}""",
            ),
        )

        val result = api.collections("lib1").results

        assertEquals("/api/libraries/lib1/collections", server.takeRequest().path)
        assertEquals(1, result.size)
        val c = result[0]
        assertEquals("Gute Nacht", c.name)
        assertNull(c.description)
        assertEquals(77L, c.lastUpdate)
        assertEquals(listOf("b2", "b1"), c.books.map { it.id })
    }

    @Test
    fun playlists_parseBookAndEpisodeItems() = runBlocking {
        val episode = """{"id":"ep1","index":1,"title":"Folge 1","duration":600.0,"audioFile":{"index":1,"ino":"5","duration":600.0,"metadata":{"filename":"e.mp3","ext":".mp3","size":10}}}"""
        server.enqueue(
            json(
                """{"playlists":[{"id":"pl1","name":"Auto","libraryId":"lib1","userId":"u","description":"d","lastUpdate":5,"createdAt":1,"items":[""" +
                    """{"libraryItemId":"b1","libraryItem":${book("b1")}},""" +
                    """{"libraryItemId":"pod1","episodeId":"ep1","episode":$episode,"libraryItem":{"id":"pod1"}},""" +
                    """{"episodeId":"orphan"}]}]}""",
            ),
        )

        val lists = api.playlists().playlists

        assertEquals("/api/playlists", server.takeRequest().path)
        val p = lists.single()
        assertEquals("Auto", p.name)
        assertEquals(2, p.items.size)
        assertEquals("b1", p.items[0].libraryItemId)
        assertNull(p.items[0].episodeId)
        assertEquals("ep1", p.items[1].episodeId)
        assertEquals("Folge 1", p.items[1].episode?.title)
        assertEquals("5", p.items[1].episode?.audioFile?.ino)
    }
}
