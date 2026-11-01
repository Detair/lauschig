package io.wolftown.lauschig.absapi.library

import io.wolftown.lauschig.absapi.AbsClientFactory
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class LibraryApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LibraryApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(LibraryApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(name: String) = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().readText())

    @Test
    fun libraries_unwrapsLibrariesObject() = runBlocking {
        server.enqueue(json("libraries.json"))

        val libs = api.libraries().libraries

        assertEquals("/api/libraries", server.takeRequest().path)
        assertEquals(listOf("book", "podcast"), libs.map { it.mediaType })
        assertEquals(1, libs[0].displayOrder)
    }

    @Test
    fun authors_unwrapsAuthorsObject_lenient() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"authors":[{"id":"a1","asin":null,"name":"Lucy Hawking","description":"Bio","imagePath":"/m/a1.jpg","numBooks":3,"addedAt":1},
                    {"id":"a2","name":"Ann","description":null}]}""",
            ),
        )

        val authors = api.authors("lib_1").authors

        assertEquals("/api/libraries/lib_1/authors", server.takeRequest().path)
        assertEquals(listOf("Lucy Hawking", "Ann"), authors.map { it.name })
        assertEquals("/m/a1.jpg", authors[0].imagePath)
        assertEquals(null, authors[1].imagePath)
        assertEquals(null, authors[1].description)
    }

    @Test
    fun items_paginates_andParsesSeriesSequence() = runBlocking {
        server.enqueue(json("library-items-page.json"))

        val page = api.items("lib_1", limit = 2, page = 1)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/libraries/lib_1/items", url.encodedPath)
        assertEquals("2", url.queryParameter("limit"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("1", url.queryParameter("minified"))
        assertEquals(42, page.total)
        assertEquals(1, page.page)
        assertEquals("3", page.results[0].media.metadata.series[0].sequence)
        // Minified list shape: no series array, series only as display string.
        assertTrue(page.results[1].media.metadata.series.isEmpty())
        assertEquals("Saga #1", page.results[1].media.metadata.seriesName)
        assertEquals(5400.25, page.results[1].media.duration!!, 0.0)
        assertEquals(1761700000000L, page.results[0].updatedAt)
        assertEquals(1700000000000L, page.results[0].addedAt)
        assertEquals(1700000000100L, page.results[1].addedAt)
        assertEquals("2020", page.results[0].media.metadata.publishedYear)
        assertEquals(null, page.results[1].media.metadata.publishedYear)
    }

    @Test
    fun itemExpanded_m4b_hasChapters() = runBlocking {
        server.enqueue(json("item-expanded-m4b.json"))

        val item = api.item("li_1")

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/items/li_1", url.encodedPath)
        assertEquals("1", url.queryParameter("expanded"))
        assertTrue(item.media.chapters.size > 1)
        assertEquals(1, item.media.audioFiles.size)
        assertEquals("2001", item.media.audioFiles[0].ino)
        assertEquals(".m4b", item.media.audioFiles[0].metadata.ext)
        assertEquals("3", item.media.metadata.series[0].sequence)
    }

    @Test
    fun itemExpanded_multiMp3_hasOrderedAudioFiles() = runBlocking {
        server.enqueue(json("item-expanded-multimp3.json"))

        val files = api.item("li_2").media.audioFiles

        assertEquals(3, files.size)
        val idx = files.map { it.index }
        assertEquals(idx.sorted(), idx)
        assertEquals(listOf(1, 2, 3), idx)
    }

    @Test
    fun audioFileUrl_buildsDownloadPath() {
        assertEquals(
            "/api/items/li_1/file/123/download",
            audioFileUrl("https://h/".toHttpUrl(), "li_1", "123").encodedPath,
        )
        assertEquals(
            "/abs/api/items/li_1/file/123/download",
            audioFileUrl("https://h/abs/".toHttpUrl(), "li_1", "123").encodedPath,
        )
        assertEquals(
            "/abs/api/items/li_1/file/123/download",
            audioFileUrl("https://h/abs".toHttpUrl(), "li_1", "123").encodedPath,
        )
    }

    @Test
    fun coverUrl_buildsCoverPath_withSubpath() {
        assertEquals("/api/items/li_1/cover", coverUrl("https://h/".toHttpUrl(), "li_1").encodedPath)
        assertEquals("/abs/api/items/li_1/cover", coverUrl("https://h/abs/".toHttpUrl(), "li_1").encodedPath)
    }

    private fun pageJson(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().readText()

    private fun jsonBody(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun items_dropsUndecodableItems_keepsOthers() = runBlocking {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(pageJson("library-items-page.json")).jsonObject
        val good = root.getValue("results").jsonArray
        val nullMeta = good[0].jsonObject.toMutableMap().apply {
            put("media", buildJsonObject { put("metadata", JsonNull) })
        }.let(::JsonObject)
        val noTitle = good[0].jsonObject.toMutableMap().apply {
            put("media", buildJsonObject { put("metadata", buildJsonObject { put("subtitle", "x") }) })
        }.let(::JsonObject)
        val results = JsonArray(listOf(nullMeta, good[0], noTitle, good[1]))
        val body = JsonObject(root + ("results" to results)).toString()
        server.enqueue(jsonBody(body))

        val page = api.items("lib_1", 4, 0)

        assertEquals(2, page.results.size)
        assertEquals("Book Three", page.results[0].media.metadata.title)
        assertEquals("Standalone", page.results[1].media.metadata.title)
    }

    @Test
    fun item_nullLists_becomeEmpty() = runBlocking {
        val body = pageJson("item-expanded-m4b.json")
            .replace(Regex("\"tags\": \\[.*?\\]", RegexOption.DOT_MATCHES_ALL), "\"tags\": null")
            .replace(Regex("\"genres\": \\[.*?\\]", RegexOption.DOT_MATCHES_ALL), "\"genres\": null")
            .replace(Regex("\"series\": \\[.*?\\]", RegexOption.DOT_MATCHES_ALL), "\"series\": null")
            .replace(Regex("\"chapters\": \\[.*?\\]", RegexOption.DOT_MATCHES_ALL), "\"chapters\": null")
        for (key in listOf("tags", "genres", "series", "chapters")) {
            assertTrue("fixture replacement for $key did not happen", body.contains("\"$key\": null"))
        }
        server.enqueue(jsonBody(body))

        val m = api.item("li_1").media

        assertTrue(m.tags.isEmpty())
        assertTrue(m.metadata.genres.isEmpty())
        assertTrue(m.metadata.series.isEmpty())
        assertTrue(m.chapters.isEmpty())
        assertEquals(1, m.audioFiles.size)
    }

    @Test
    fun results_nonArray_throws() {
        for (bad in listOf("{}", "\"oops\"", "5")) {
            server.enqueue(jsonBody("""{"results":$bad,"total":0,"limit":1,"page":0}"""))
            val ex = runCatching { runBlocking { api.items("lib_1", 1, 0) } }.exceptionOrNull()
            assertTrue("expected SerializationException for $bad but got $ex", ex is kotlinx.serialization.SerializationException)
        }
    }

    @Test
    fun results_null_isEmpty() = runBlocking {
        server.enqueue(jsonBody("""{"results":null,"total":0,"limit":1,"page":0}"""))
        assertTrue(api.items("lib_1", 1, 0).results.isEmpty())
    }
}
