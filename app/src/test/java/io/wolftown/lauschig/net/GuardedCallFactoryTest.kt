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
    private fun setup(server: MockWebServer, online: Boolean): GuardedCallFactory {
        val base = server.url("/")
        val store = FakeAccountStore()
        store.acc.value = Account("s", "u", "n", false, ServerAddresses(base, null), setOf(base.cleartextKey()))
        store.access = "tok"
        val state = if (online) ConnectionState.Online(base, ConnectionState.Via.LOCAL) else ConnectionState.Offline
        val api = ApiProvider(flowOf(state), OkHttpClient(), store, CoroutineScope(Dispatchers.Unconfined))
        return GuardedCallFactory(api, CoroutineScope(Dispatchers.Unconfined), visible = flowOf(true))
    }

    @Test fun requestRightAfterConstruction_whenOnline_isAuthenticated() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x"))
            val factory = setup(server, online = true)
            factory.newCall(Request.Builder().url(server.url("/api/items/i/cover")).build()).execute().close()
            assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
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
