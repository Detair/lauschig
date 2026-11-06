package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.ui.common.GuardedCallFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardedCallFactoryTest {
    private val store = FakeAccountStore()

    private fun setup(server: MockWebServer, online: Boolean, confirmed: Boolean = true): GuardedCallFactory {
        val base = server.url("/")
        store.acc.value = Account("s", "u", "n", false, ServerAddresses(base, null), if (confirmed) setOf(base.cleartextKey()) else emptySet())
        store.access = "tok"
        val state = if (online) ConnectionState.Online(base, ConnectionState.Via.LOCAL) else ConnectionState.Offline
        val api = ApiProvider(flowOf(state), OkHttpClient(), store, CoroutineScope(Dispatchers.Unconfined))
        return GuardedCallFactory(api, CoroutineScope(Dispatchers.Unconfined), visible = flowOf(true))
    }

    /** The cover route is public (server 2.26 and later), so a page never needs a token, and none can be the wrong one. */
    @Test fun aCover_goesOutWithoutAnyToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = true)
            factory.newCall(Request.Builder().url(server.url("/api/items/i/cover")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    /** Review I2: a tile of A's page on the same server, requested after the switch. */
    @Test fun aCoverOfTheAccountBefore_doesNotCarryTheNextAccountsToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = true)
            store.acc.value = store.acc.value!!.copy(serverId = "b", userId = "u2")
            store.access = "token-of-b"
            factory.newCall(Request.Builder().url(server.url("/api/items/of-a/cover?ts=1")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun aCover_isNotRefusedForAnAccountThatChanged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = true)
            // Made for "s" it would throw AccountChangedException; a cover belongs to nobody.
            store.acc.value = store.acc.value!!.copy(serverId = "b")
            factory.newCall(Request.Builder().url(server.url("/api/items/i/cover")).build()).execute().use { assertEquals(200, it.code) }
        }
    }

    @Test fun plainHttp_stillOnlyToAConfirmedServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = true, confirmed = false)
            val failure = runCatching { factory.newCall(Request.Builder().url(server.url("/api/items/i/cover")).build()).execute().close() }.exceptionOrNull()
            assertEquals(CleartextNotConfirmedException::class, failure?.let { it::class })
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun offline_requestCarriesNoCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = false)
            runCatching { factory.newCall(Request.Builder().url(server.url("/api/items/i/cover")).build()).execute().close() }
            assertNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.getHeader("Authorization"))
        }
    }

    @Test fun connectionProbe_runsOnlyWhileAppIsVisible() = runTest {
        var started = 0
        var active = 0
        val probe = flow<ConnectionState> {
            started++; active++
            try { emit(ConnectionState.Offline); awaitCancellation() } finally { active-- }
        }
        val api = ApiProvider(probe, OkHttpClient(), FakeAccountStore(), backgroundScope)
        val visible = MutableStateFlow(false)
        GuardedCallFactory(api, backgroundScope, visible)
        runCurrent()
        assertEquals("no probe while in background", 0, started)

        visible.value = true; runCurrent()
        assertEquals(1, active)

        visible.value = false
        advanceTimeBy(10_000); runCurrent()
        assertEquals("probe stops after going to background", 0, active)
    }
}
