package io.wolftown.lauschig.cast

import android.app.Application
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.playback.PlaybackSessionApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The session client against a real local server with the route's shape (MockWebServer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastSessionsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PlaybackSessionApi
    private val store = FakeCastSessionStore()
    private val accounts = FakeAccountStore()
    private var online = true

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(PlaybackSessionApi::class.java)
        accounts.acc.value = account("a")
    }

    @After fun tearDown() = server.shutdown()

    private fun account(id: String) = Account(id, "u", "n", false, ServerAddresses("http://192.168.1.2:13378".toHttpUrl(), "https://abs.example".toHttpUrl()), emptySet())

    private val sessions by lazy {
        CastSessions(
            api = { sid -> flowOf(api.takeIf { online && sid == accounts.acc.value?.serverId }) },
            deviceInfo = { DeviceInfoDto(deviceId = "dev") }, store = store, accounts = accounts,
            scope = CoroutineScope(Dispatchers.Unconfined), waitMs = 300,
        )
    }

    private fun opened(id: String = "sess-1") = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"id":"$id","audioTracks":[{"index":2,"startOffset":10,"duration":20,"mimeType":"audio/mp4"},{"index":1,"startOffset":0,"duration":10}]}""",
    )

    @Test fun aBookOpens_itsTracksInOrder_andTheSessionIsNoted() = runBlocking {
        server.enqueue(opened())
        val s = sessions.open("a", "li")
        assertEquals("/api/items/li/play", server.takeRequest().path)
        assertEquals("sess-1", s.sessionId)
        assertEquals(listOf(1, 2), s.tracks.map { it.index })
        assertEquals(listOf("audio/mpeg", "audio/mp4"), s.tracks.map { it.mimeType })
        assertEquals(listOf("a" to "sess-1"), store.open.toList())
    }

    @Test fun anEpisodeOpensUnderItsPodcast() = runBlocking {
        server.enqueue(opened())
        sessions.open("a", "pod~ep")
        assertEquals("/api/items/pod/play/ep", server.takeRequest().path)
    }

    @Test fun offlineOrAnotherAccount_opensNothing() = runBlocking {
        online = false
        try {
            sessions.open("a", "li")
            fail("opened while offline")
        } catch (e: CastServerOffline) {
            assertEquals(0, server.requestCount)
        }
        online = true
        try {
            sessions.open("someone-else", "li")
            fail("opened for another account")
        } catch (e: CastServerOffline) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun aRefusedOpen_leavesNoNote() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            sessions.open("a", "li")
            fail("the server said no")
        } catch (e: retrofit2.HttpException) {
            assertTrue(store.open.isEmpty())
        }
    }

    @Test fun close_endsTheSession_andForgetsIt() = runBlocking {
        server.enqueue(opened())
        val s = sessions.open("a", "li")
        server.takeRequest()
        server.enqueue(MockResponse().setBody("OK"))
        sessions.close(s)
        val closing = server.takeRequest()
        assertEquals("/api/session/sess-1/close", closing.path)
        assertEquals(null, closing.getHeader("Range"))
        awaitEmpty()
    }

    @Test fun aSessionTheServerNoLongerKnows_isForgottenToo() = runBlocking {
        store.add("a", "gone")
        server.enqueue(MockResponse().setResponseCode(404))
        sessions.closeOpen("a")
        assertTrue(store.open.isEmpty())
    }

    @Test fun aServerError_keepsTheNote_forTheNextStart() = runBlocking {
        store.add("a", "s")
        server.enqueue(MockResponse().setResponseCode(500))
        sessions.closeOpen("a")
        assertEquals(listOf("a" to "s"), store.open.toList())
    }

    /** A sign-out or a switch waits for this before the token goes; the other account's sessions are not its business. */
    @Test fun closeOpen_closesOnlyThatAccountsSessions() = runBlocking {
        store.add("a", "mine")
        store.add("b", "other")
        server.enqueue(MockResponse().setBody("OK"))
        sessions.closeOpen("a")
        assertEquals("/api/session/mine/close", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("b" to "other"), store.open.toList())
    }

    /** After a process death: the active account's go, a saved profile's wait for it, an account that is gone is the server's. */
    @Test fun theNextStart_closesWhatItCan() = runBlocking {
        store.add("a", "active")
        store.add("saved", "waits")
        store.add("gone", "nobody")
        accounts.aside.value = listOf(FakeAccountStore.Aside(account("saved"), null, null, false))
        server.enqueue(MockResponse().setBody("OK"))
        sessions.closeLeftovers()
        assertEquals("/api/session/active/close", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("saved" to "waits"), store.open.toList())
    }

    @Test fun theNextStart_offline_keepsTheNote() = runBlocking {
        store.add("a", "s")
        online = false
        sessions.closeLeftovers()
        assertEquals(listOf("a" to "s"), store.open.toList())
    }

    @Test fun nothingLeftOpen_asksNoServer() = runBlocking {
        sessions.closeLeftovers()
        assertEquals(0, server.requestCount)
    }

    @Test fun theAddresses_arePublicFirst_andOnlyForTheActiveAccount() = runBlocking {
        assertEquals(listOf("https://abs.example/", "http://192.168.1.2:13378/"), sessions.addresses("a").map { it.toString() })
        assertFalse(sessions.addresses("b").isNotEmpty())
    }

    private suspend fun awaitEmpty() {
        repeat(100) {
            if (store.open.isEmpty()) return
            kotlinx.coroutines.delay(10)
        }
        fail("the note stayed: ${store.open}")
    }
}
