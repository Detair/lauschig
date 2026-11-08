package io.wolftown.lauschig.absapi.playback

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** The route's shape as ABS 2.37.1 answers it (`POST /api/items/<id>/play`, seen on the local test server). */
class PlaybackSessionApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PlaybackSessionApi
    private val device = DeviceInfoDto(deviceId = "dev", clientName = "Lauschig")

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(PlaybackSessionApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private val session = """
        {"id":"sess-1","userId":"u","libraryItemId":"li","mediaPlayer":"chromecast","playMethod":0,"duration":720,
         "libraryItem":{"id":"li"},"chapters":[],"deviceInfo":{"id":"d"},
         "audioTracks":[
           {"index":1,"ino":"335188","startOffset":0,"duration":240,"mimeType":"audio/mpeg","title":"01.mp3","contentUrl":"/api/items/li/file/335188","metadata":{"path":"/a"}},
           {"index":2,"ino":"335189","startOffset":240,"duration":240.5,"mimeType":"audio/mp4","title":"02.m4b","contentUrl":"/api/items/li/file/335189"},
           {"ino":"335190","startOffset":"480.5","duration":"240"}
         ]}
    """.trimIndent()

    @Test
    fun aBook_opensADirectPlaySessionNamedForTheReceiver() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(session))
        val opened = api.start("li", PlaybackSessionRequest(device, forceDirectPlay = true, mediaPlayer = "chromecast"))
        assertEquals("sess-1", opened.id)
        assertEquals(listOf(1, 2, null), opened.audioTracks.map { it.index })
        assertEquals(listOf("audio/mpeg", "audio/mp4", null), opened.audioTracks.map { it.mimeType })
        assertEquals(480.5, opened.audioTracks[2].startOffset, 0.001)
        assertEquals(240.0, opened.audioTracks[2].duration, 0.001)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/items/li/play", request.path)
        // Sent explicitly: without forceDirectPlay the server may transcode, and the track route then serves a playlist.
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertTrue(body.getValue("forceDirectPlay").jsonPrimitive.boolean)
        assertEquals("chromecast", body.getValue("mediaPlayer").jsonPrimitive.content)
        assertEquals("dev", body.getValue("deviceInfo").jsonObject.getValue("deviceId").jsonPrimitive.content)
    }

    @Test
    fun anEpisode_opensUnderItsPodcast() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"s","audioTracks":[{"index":1,"startOffset":0,"duration":60,"mimeType":"audio/mpeg"}]}"""))
        val opened = api.startEpisode("pod", "ep", PlaybackSessionRequest(device, forceDirectPlay = true, mediaPlayer = "chromecast"))
        assertEquals("/api/items/pod/play/ep", server.takeRequest().path)
        assertEquals(1, opened.audioTracks.single().index)
    }

    @Test
    fun close_postsNothing_andAClosedSessionIsNoError() = runBlocking {
        server.enqueue(MockResponse().setBody("OK"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        assertTrue(api.close("sess-1").isSuccessful)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/session/sess-1/close", request.path)
        assertEquals(0L, request.bodySize)
        assertFalse(api.close("sess-1").isSuccessful)
    }

    @Test
    fun theTrackUrl_isTheSessionsPublicRoute_alsoUnderASubPath() {
        assertEquals("https://abs.example/public/session/s%201/track/2", sessionTrackUrl("https://abs.example".toHttpUrl(), "s 1", 2).toString())
        assertEquals("https://abs.example/abs/public/session/s/track/1", sessionTrackUrl("https://abs.example/abs/".toHttpUrl(), "s", 1).toString())
        assertEquals("http://10.0.0.2:13378/public/session/s/track/3", sessionTrackUrl("http://10.0.0.2:13378".toHttpUrl(), "s", 3).toString())
    }

    @Test
    fun theTrackUrl_carriesNoQueryNoFragmentNoCredentials() {
        val url = sessionTrackUrl("https://abs.example".toHttpUrl(), "s", 1)
        assertNull(url.query)
        assertNull(url.fragment)
        assertEquals("", url.username)
        assertEquals("", url.password)
    }
}
