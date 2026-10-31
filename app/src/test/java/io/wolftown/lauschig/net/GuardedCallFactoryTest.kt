package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.ui.common.GuardedCallFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
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
        return GuardedCallFactory(api, CoroutineScope(Dispatchers.Unconfined))
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
}
