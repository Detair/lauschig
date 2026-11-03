package io.wolftown.lauschig.absapi.admin

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.library.LibraryApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class AdminApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AdminApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/abs/"), OkHttpClient()).create(AdminApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun scanLibrary_acceptsThePlainOkBody() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))
        api.scanLibrary("lib1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/abs/api/libraries/lib1/scan", req.path)
    }

    @Test fun nonAdmin_getsForbidden() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        try {
            api.scanLibrary("lib1")
            fail("expected 403")
        } catch (e: HttpException) {
            assertEquals(403, e.code())
        }
    }

    @Test fun scanItem_result() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"result":"UPDATED"}"""))
        assertEquals("UPDATED", api.scanItem("li1").result)
        assertEquals("/abs/api/items/li1/scan", server.takeRequest().path)
    }

    @Test fun match_sendsOnlyTheProvider() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"updated":true,"libraryItem":{"id":"li1"}}"""))
        assertTrue(api.matchItem("li1", MatchRequest("audible.de")).updated)
        val req = server.takeRequest()
        assertEquals("/abs/api/items/li1/match", req.path)
        assertEquals("""{"provider":"audible.de"}""", req.body.readUtf8())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"warning":"No audible.de match found"}"""))
        val r = api.matchItem("li1", MatchRequest("audible.de"))
        assertEquals(false, r.updated)
        assertEquals("No audible.de match found", r.warning)
    }

    @Test fun onlineUsers_withSessions() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"usersOnline":[{"id":"u1","username":"matthias","type":"user","session":{"id":"s1","userId":"u1","displayTitle":"Der Anfang","currentTime":"61.5","duration":3600},"lastSeen":1,"createdAt":0,"connections":2}],
                   "openSessions":[{"id":"s1","userId":"u1","libraryItemId":"li1","displayTitle":"Der Anfang","displayAuthor":"Test Autor","currentTime":61.5,"duration":3600,"mediaPlayer":"html5","deviceInfo":{"clientName":"Abs Web","browserName":"Firefox","osName":"Linux"},"updatedAt":5,"chapters":[]}]}""",
            ),
        )
        val r = api.onlineUsers()
        assertEquals("matthias", r.usersOnline.single().username)
        assertEquals(2, r.usersOnline.single().connections)
        assertEquals(61.5, r.usersOnline.single().session!!.currentTime, 0.0)
        val s = r.openSessions.single()
        assertEquals("Der Anfang", s.displayTitle)
        assertEquals("Abs Web", s.deviceInfo!!.clientName)
        assertEquals("/abs/api/users/online", server.takeRequest().path)
    }

    @Test fun libraries_carryTheirProvider() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"libraries":[{"id":"l1","name":"Hörbücher","mediaType":"book","provider":"audible.de"},{"id":"l2","name":"Alt","mediaType":"book"}]}"""),
        )
        val libs = AbsClientFactory.retrofit(server.url("/abs/"), OkHttpClient()).create(LibraryApi::class.java).libraries().libraries
        assertEquals(listOf("audible.de", null), libs.map { it.provider })
    }
}
