package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    private class FakeStore : AccountStore {
        val acc = MutableStateFlow<Account?>(null)
        val reauth = MutableStateFlow(false)
        var access: String? = null
        var refresh: String? = null
        override val account: Flow<Account?> = acc
        override val needsReauth: Flow<Boolean> = reauth
        override suspend fun save(a: Account, accessToken: String, refreshToken: String) {
            acc.value = a; access = accessToken; refresh = refreshToken; reauth.value = false
        }
        override suspend fun accessToken() = access
        override suspend fun refreshToken() = refresh
        override suspend fun updateTokens(access: String, refresh: String) {
            this.access = access; this.refresh = refresh; reauth.value = false
        }
        override suspend fun markNeedsReauth() { reauth.value = true }
        override suspend fun clear(keepDownloads: Boolean) { acc.value = null; access = null; refresh = null }
    }

    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var client: OkHttpClient
    private val refreshCalls = AtomicInteger()

    private val refreshBody = """{"user":{"id":"u1","username":"a","type":"user","accessToken":"new-access","refreshToken":"new-refresh"}}"""

    /** Serves 401 for the old token, 200 for the new one; refresh behaviour is configurable. */
    private fun serve(refresh: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/auth/refresh") {
                    refreshCalls.incrementAndGet()
                    return refresh(request)
                }
                return if (request.getHeader("Authorization") == "Bearer new-access")
                    MockResponse().setResponseCode(200).setBody("ok")
                else MockResponse().setResponseCode(401)
            }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        store = FakeStore().apply {
            runBlocking {
                save(
                    Account("s", "u1", "a", false, ServerAddresses(null, server.url("/")), emptySet()),
                    "old-access", "old-refresh",
                )
            }
        }
        val base = OkHttpClient()
        client = base.newBuilder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, base, server.url("/")))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get() = client.newCall(Request.Builder().url(server.url("/api/x")).build()).execute()

    @Test
    fun expiredAccess_refreshesOnce_andRetries() {
        serve { MockResponse().setBody(refreshBody) }
        val r = get()
        assertEquals(200, r.code)
        assertEquals(1, refreshCalls.get())
        assertEquals("new-access", store.access)
        assertEquals("new-refresh", store.refresh)
        val seen = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
        assertEquals("old-refresh", seen.first { it.path == "/auth/refresh" }.getHeader("x-refresh-token"))
        assertEquals("Bearer new-access", seen.last().getHeader("Authorization"))
    }

    @Test
    fun refreshWithoutNewRefreshToken_keepsOldOne() {
        serve { MockResponse().setBody("""{"user":{"id":"u1","username":"a","type":"user","accessToken":"new-access"}}""") }
        assertEquals(200, get().code)
        assertEquals("new-access", store.access)
        assertEquals("old-refresh", store.refresh)
    }

    @Test
    fun refreshRejected_marksNeedsReauth_keepsAccount() = runBlocking {
        serve { MockResponse().setResponseCode(401) }
        val r = get()
        assertEquals(401, r.code)
        assertTrue(store.reauth.value)
        assertNotNull(store.account.first())
        assertEquals("old-refresh", store.refresh)
    }

    @Test
    fun refreshNetworkError_doesNotMarkReauth() {
        serve { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val r = get()
        assertEquals(401, r.code)
        assertFalse(store.reauth.value)
        assertEquals("old-refresh", store.refresh)
    }

    @Test
    fun concurrent401s_triggerSingleRefresh() = runBlocking {
        serve { MockResponse().setBody(refreshBody).setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val codes = withContext(Dispatchers.IO) {
            listOf(async { get().code }, async { get().code }).awaitAll()
        }
        assertEquals(listOf(200, 200), codes)
        assertEquals(1, refreshCalls.get())
    }
}
