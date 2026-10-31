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
}
