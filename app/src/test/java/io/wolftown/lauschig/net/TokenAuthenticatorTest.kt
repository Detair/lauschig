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
    private lateinit var server: MockWebServer
    private lateinit var store: FakeAccountStore
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
        store = FakeAccountStore().apply {
            runBlocking {
                save(
                    Account("s", "u1", "a", false, ServerAddresses(null, server.url("/")), emptySet()),
                    "old-access", "old-refresh",
                )
            }
        }
        val base = OkHttpClient()
        client = base.newBuilder()
            .addInterceptor(AuthInterceptor(store, server.url("/")))
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

    @Test
    fun undecryptableToken_marksNeedsReauth() {
        serve { MockResponse().setBody(refreshBody) }
        store.refresh = null // ciphertext present but undecryptable -> reads as null
        assertEquals(401, get().code)
        assertTrue(store.reauth.value)
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun foreignHost401_doesNotAttachToken() {
        serve { MockResponse().setBody(refreshBody) }
        val other = MockWebServer().also { it.start() }
        try {
            other.enqueue(MockResponse().setResponseCode(401))
            val r = client.newCall(Request.Builder().url(other.url("/x")).build()).execute()
            assertEquals(401, r.code)
            assertEquals(null, other.takeRequest().getHeader("Authorization"))
            assertEquals(0, refreshCalls.get())
            assertEquals(1, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun storageFailureOnUpdate_doesNotCrashOrMarkReauth() {
        serve { MockResponse().setBody(refreshBody) }
        store.failUpdate = true
        assertEquals(401, get().code)
        assertFalse(store.reauth.value)
    }

    @Test
    fun refreshRedirect_isNotFollowed_refreshTokenStaysOnServer() {
        MockWebServer().use { other ->
            other.enqueue(MockResponse().setBody(refreshBody))
            serve { MockResponse().setResponseCode(307).setHeader("Location", other.url("/auth/refresh")) }
            val r = get()
            assertEquals(401, r.code)
            r.close()
            assertEquals("redirect target never sees the refresh token", 0, other.requestCount)
            assertEquals("old-refresh", store.refresh)
            assertFalse(store.reauth.value)
        }
    }

    // Profile switch (Phase 6, Task 12.1): tokens stay with the account that sent the request.

    private val other = Account("t", "u2", "b", false, ServerAddresses(null, null), emptySet())

    @Test
    fun aRequestOfTheOldAccount_isNeverRetriedWithTheNewAccountsToken() {
        val seen = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/auth/refresh") { refreshCalls.incrementAndGet(); return MockResponse().setBody(refreshBody) }
                seen += request.getHeader("Authorization")
                // The switch happens while the old account's request is on its way; the server answers 401.
                runBlocking { store.add(other, "other-access", "other-refresh") }
                return MockResponse().setResponseCode(401)
            }
        }
        assertEquals(401, get().code)
        assertEquals(listOf<String?>("Bearer old-access"), seen)
        assertEquals(0, refreshCalls.get())
        // Nothing written anywhere: the new account keeps its tokens, the old one (set aside) its own.
        assertEquals("other-access", store.access)
        val old = store.aside.value.single()
        assertEquals(listOf("s", "old-access", "old-refresh"), listOf(old.account.serverId, old.access, old.refresh))
        assertFalse(old.reauth)
        assertFalse(store.reauth.value)
    }

    @Test
    fun aRefreshThatLandsAfterASwitch_storesTheTokensWithItsOwnAccount_andDoesNotRetry() {
        serve {
            // The switch happens while the refresh is on its way.
            runBlocking { store.add(other, "other-access", "other-refresh") }
            MockResponse().setBody(refreshBody)
        }
        assertEquals(401, get().code)
        assertEquals(1, refreshCalls.get())
        assertEquals("other-access", store.access)
        assertEquals("other-refresh", store.refresh)
        val old = store.aside.value.single()
        assertEquals("s", old.account.serverId)
        assertEquals("new-access", old.access)
        assertEquals("new-refresh", old.refresh)
        // Only the old token went out for this request.
        val auth = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.filter { it.path == "/api/x" }.map { it.getHeader("Authorization") }.toList()
        assertEquals(listOf("Bearer old-access"), auth)
    }

    @Test
    fun aRejectedRefreshAfterASwitch_flagsTheOldAccount_notTheNewOne() {
        serve {
            runBlocking { store.add(other, "other-access", "other-refresh") }
            MockResponse().setResponseCode(401)
        }
        assertEquals(401, get().code)
        assertFalse(store.reauth.value)
        assertTrue(store.aside.value.single().reauth)
    }
}
