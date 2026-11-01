package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.AbsClientFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MeApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(MeApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun me_parsesMediaProgress() = runBlocking {
        val body = checkNotNull(javaClass.getResourceAsStream("/fixtures/me.json")).bufferedReader().readText()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))

        val me = api.me()

        assertEquals("/api/me", server.takeRequest().path)
        assertNull(me.accessToken)
        assertEquals(2, me.mediaProgress.size)
        assertFalse(me.mediaProgress[0].isFinished)
        assertTrue(me.mediaProgress[1].isFinished)
        assertEquals(0.25, me.mediaProgress[0].progress, 0.0)
        assertNull(me.mediaProgress[0].episodeId)
    }

    @Test
    fun updateProgress_patchesItemProgress() = runBlocking {
        // The real server answers 200 with the plain text body "OK".
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))

        api.updateProgress("li_1", ProgressUpdate(120.5, 3600.0, 0.0335, false))

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/li_1", req.path)
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(120.5, sent.getValue("currentTime").jsonPrimitive.double, 0.0)
        assertFalse(sent.getValue("isFinished").jsonPrimitive.boolean)
    }

    @Test
    fun updateEpisodeProgress_patchesEpisodePath() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8").setBody("OK"))

        api.updateEpisodeProgress("li_pod1", "ep_2", ProgressUpdate(10.0, 1800.0, 0.005, false))

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/li_pod1/ep_2", req.path)
    }

    @Test
    fun localAll_postsSessions() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"results":[{"id":"s1","success":true,"progressSynced":true},{"id":"s2","success":false,"error":"Media item not found"}]}""",
            ),
        )
        val session = LocalSessionDto(
            id = "s1", libraryItemId = "li_1", episodeId = null, mediaType = "book",
            displayTitle = "Book Three", duration = 7800.75, startTime = 100.0, currentTime = 400.0,
            timeListening = 295.5, startedAt = 1761900000000, updatedAt = 1761900300000,
            deviceInfo = DeviceInfoDto(deviceId = "dev-1", clientName = "Lauschig", model = "Pixel"),
            date = "2026-10-31", dayOfWeek = "Saturday",
        )

        val result = api.syncLocalSessions(LocalSessionsBody(listOf(session), session.deviceInfo))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/session/local-all", req.path)
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val s0 = sent.getValue("sessions").jsonArray[0].jsonObject
        assertEquals(295.5, s0.getValue("timeListening").jsonPrimitive.double, 0.0)
        assertEquals("li_1", s0.getValue("libraryItemId").jsonPrimitive.content)
        assertEquals(3, s0.getValue("playMethod").jsonPrimitive.int)
        assertFalse(s0.containsKey("episodeId"))
        assertEquals("dev-1", sent.getValue("deviceInfo").jsonObject.getValue("deviceId").jsonPrimitive.content)
        assertEquals(listOf(true, false), result.results.map { it.success })
        assertEquals(true, result.results[0].progressSynced)
        assertEquals("Media item not found", result.results[1].error)
    }

    @Test
    fun me_dropsMalformedProgressRows_keepsValidOnes() = runBlocking {
        val good = """{"libraryItemId":"li_1","currentTime":1.0,"duration":2.0,"progress":0.5,"isFinished":false,"lastUpdate":5}"""
        val noItem = """{"currentTime":1.0,"duration":2.0,"progress":0.5,"isFinished":false,"lastUpdate":6}"""
        val broken = """{"libraryItemId":"li_2","currentTime":"abc"}"""
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"u","username":"a","type":"user","mediaProgress":[$good,$broken,$noItem,17]}""",
            ),
        )

        val me = api.me()

        assertEquals(2, me.mediaProgress.size)
        assertEquals("li_1", me.mediaProgress[0].libraryItemId)
        assertNull(me.mediaProgress[1].libraryItemId)
    }
}
