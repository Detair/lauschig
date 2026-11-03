package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ServerLogoutTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun account(base: HttpUrl) = Account("aaaaaaaaaaaaaaaa", "u1", "fabian", false, ServerAddresses(base, null), emptySet())

    private fun logout(state: ConnectionState, client: OkHttpClient = OkHttpClient()) = ServerLogout({ state }, client)

    @Test fun online_postsTheRefreshToken_withoutCredentials() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"redirect_url":null}"""))
        val base = server.url("/")

        assertTrue(logout(ConnectionState.Online(base, ConnectionState.Via.LOCAL)).logout(account(base), "refresh-1"))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/logout", req.path)
        assertEquals("refresh-1", req.getHeader("x-refresh-token"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun aRedirectToAnotherHost_isNotFollowed() = runBlocking {
        // The refresh token goes to the account's server only, never where a redirect points.
        val elsewhere = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", elsewhere.url("/logout").toString()))
            val base = server.url("/")
            assertFalse(logout(ConnectionState.Online(base, ConnectionState.Via.LOCAL)).logout(account(base), "refresh-1"))
            assertEquals(1, server.requestCount)
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test fun offline_sendsNothing() = runBlocking {
        assertFalse(logout(ConnectionState.Offline).logout(account(server.url("/")), "refresh-1"))
        assertEquals(0, server.requestCount)
    }

    @Test fun anotherServerOnline_sendsNothing() = runBlocking {
        // Right after a switch the shared state may still replay the previous server: its host never gets the token.
        val other = "http://other.example:13378/".toHttpUrl()
        assertFalse(logout(ConnectionState.Online(other, ConnectionState.Via.LOCAL)).logout(account(server.url("/")), "refresh-1"))
        assertEquals(0, server.requestCount)
    }

    @Test fun serverError_isFalse_notAnException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val base = server.url("/")
        assertFalse(logout(ConnectionState.Online(base, ConnectionState.Via.LOCAL)).logout(account(base), "refresh-1"))
    }

    @Test fun unreachable_isFalse() = runBlocking {
        val base = server.url("/")
        server.shutdown()
        assertFalse(logout(ConnectionState.Online(base, ConnectionState.Via.LOCAL)).logout(account(base), "refresh-1"))
    }

    @Test fun slowServer_givesUpAfterTheTimeout() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(10, TimeUnit.SECONDS).setBody("{}"))
        val base = server.url("/")
        val started = System.nanoTime()
        assertFalse(ServerLogout({ ConnectionState.Online(base, ConnectionState.Via.LOCAL) }, OkHttpClient(), timeoutMs = 300).logout(account(base), "refresh-1"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }
}
