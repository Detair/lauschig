package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A request carries the token of the account it was made for, from its first send (fix round 1, E1): a client of
 * [ApiProvider.clientFor] belongs to the account signed in when it was built. After a switch its requests are not sent
 * at all, and no account's token reaches a server that is not one of that account's addresses.
 */
class AccountBoundClientTest {
    /** Server X (account A, and in one test B too) and server Y (account B on another server). */
    private val x = MockWebServer().also { it.start() }
    private val y = MockWebServer().also { it.start() }
    private val seenX = CopyOnWriteArrayList<String?>()
    private val seenY = CopyOnWriteArrayList<String?>()

    private val a = Account("aaaaaaaaaaaaaaaa", "u1", "fabian", false, ServerAddresses(x.url("/"), null), setOf("http://${x.hostName}:${x.port}"))
    private val bOnY = Account("bbbbbbbbbbbbbbbb", "u2", "matthias", false, ServerAddresses(y.url("/"), null), setOf("http://${y.hostName}:${y.port}"))
    private val bOnX = Account("cccccccccccccccc", "u3", "lukas", false, ServerAddresses(x.url("/"), null), setOf("http://${x.hostName}:${x.port}"))

    private val store = FakeAccountStore().apply { runBlocking { save(a, "a-access", "a-refresh") } }
    private var refreshes = 0

    init {
        fun answer(seen: MutableList<String?>) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/auth/refresh") { refreshes++; return MockResponse().setResponseCode(401) }
                val auth = request.getHeader("Authorization")
                seen += auth
                return if (auth != null) MockResponse().setBody("""{"libraries":[]}""") else MockResponse().setResponseCode(401)
            }
        }
        x.dispatcher = answer(seenX)
        y.dispatcher = answer(seenY)
    }

    @After fun tearDown() { x.shutdown(); y.shutdown() }

    /** The provider as the app has it; its state says "online at X" (also stale, right after a switch). */
    private val api = ApiProvider(flowOf(ConnectionState.Online(x.url("/"), ConnectionState.Via.LOCAL)), OkHttpClient(), store, CoroutineScope(Dispatchers.Unconfined))

    /** The client of whoever is signed in right now. */
    private fun signedInClient(base: okhttp3.HttpUrl) = api.clientFor(store.acc.value?.serverId, base)

    private fun get(client: OkHttpClient, server: MockWebServer) = client.newCall(Request.Builder().url(server.url("/api/me")).build()).execute()

    @Test fun aClient_carriesTheTokenOfItsAccount() {
        get(signedInClient(x.url("/")), x).close()
        assertEquals(listOf<String?>("Bearer a-access"), seenX)
    }

    @Test fun differentServers_aRequestOfTheOldAccount_neverCarriesTheNewAccountsToken_andTheNewOneIsNotFlagged() {
        val made = signedInClient(x.url("/")) // made for A, e.g. a sync that is still queued
        runBlocking { store.add(bOnY, "b-access", "b-refresh") }

        // It is not sent at all.
        val failed = runCatching { get(made, x) }.exceptionOrNull()
        assertTrue(failed is AccountChangedException)
        // And a client built now – while the shared state still says X – does not hand B's token to X either.
        val stale = signedInClient(x.url("/"))
        assertNotSame(made, stale)
        assertTrue(runCatching { get(stale, x) }.exceptionOrNull() is AccountChangedException)

        assertEquals(emptyList<String?>(), seenX)
        assertEquals(0, x.requestCount)
        assertEquals(0, refreshes)
        assertFalse(store.reauth.value)
        assertFalse(store.aside.value.single().reauth)
    }

    @Test fun sameServer_aRequestMadeForTheOldAccount_doesNotRunAsTheNewOne() {
        val made = signedInClient(x.url("/"))
        runBlocking { store.add(bOnX, "b-access", "b-refresh") }
        assertTrue(runCatching { get(made, x) }.exceptionOrNull() is AccountChangedException)
        assertEquals(0, x.requestCount)
        // The new account's own client works as before.
        get(signedInClient(x.url("/")), x).close()
        assertEquals(listOf<String?>("Bearer b-access"), seenX)
        assertFalse(store.reauth.value)
    }

    @Test fun theClientOfANamedAccount_sendsOnlyWhileItIsTheSignedInOne_withItsOwnToken() {
        // Asked for B while A is signed in: it does not run as A, and B's turn has not come.
        assertTrue(runCatching { get(api.clientFor(bOnX.serverId, x.url("/")), x) }.exceptionOrNull() is AccountChangedException)
        assertEquals(0, x.requestCount)
        runBlocking { store.add(bOnX, "b-access", "b-refresh") }
        // A's client stays A's; B's is B's, with the state and the signed-in account not asked at all.
        assertTrue(runCatching { get(api.clientFor(a.serverId, x.url("/")), x) }.exceptionOrNull() is AccountChangedException)
        get(api.clientFor(bOnX.serverId, x.url("/")), x).close()
        assertEquals(listOf<String?>("Bearer b-access"), seenX)
    }

    @Test fun afterASignOut_aRequestOfTheAccount_isNotSent() {
        val made = signedInClient(x.url("/"))
        runBlocking { store.clear(keepDownloads = false) }
        assertTrue(runCatching { get(made, x) }.exceptionOrNull() is IOException)
        assertEquals(0, x.requestCount)
    }

    @Test fun anAddressTheAccountDoesNotHave_getsNoToken() {
        // Y is not one of A's addresses: a client for it (a stale state of another server) sends nothing.
        assertTrue(runCatching { get(signedInClient(y.url("/")), y) }.exceptionOrNull() is AccountChangedException)
        assertEquals(0, y.requestCount)
    }

    // Fix round 2, N6/N5: the address switching the app lives on, and the Retrofit path.

    /** Fabian at home (X, local) and on the way (Y, public): one account, two addresses. */
    private val both = Account(
        "dddddddddddddddd", "u4", "fabian", false, ServerAddresses(x.url("/"), y.url("/")),
        setOf("http://${x.hostName}:${x.port}", "http://${y.hostName}:${y.port}"),
    )

    @Test fun anAccountWithALocalAndAPublicAddress_getsItsTokenOnBoth_whicheverIsUsed() {
        runBlocking { store.save(both, "a-access", "a-refresh") }
        get(signedInClient(x.url("/")), x).close()
        get(signedInClient(y.url("/")), y).close() // the address changes (left the home Wi-Fi) …
        get(signedInClient(x.url("/")), x).close() // … and back
        assertEquals(listOf<String?>("Bearer a-access", "Bearer a-access"), seenX)
        assertEquals(listOf<String?>("Bearer a-access"), seenY)
    }

    @Test fun unauthorizedOnBothAddresses_refreshesOnce_andBothUseTheNewToken() {
        runBlocking { store.save(both, "a-access", "a-refresh") }
        val refreshCalls = java.util.concurrent.atomic.AtomicInteger()
        val seen = CopyOnWriteArrayList<String?>()
        val answer = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/auth/refresh") {
                    refreshCalls.incrementAndGet()
                    return MockResponse().setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .setBody("""{"user":{"id":"u4","username":"fabian","type":"user","accessToken":"new-access","refreshToken":"new-refresh"}}""")
                }
                seen += request.getHeader("Authorization")
                return if (request.getHeader("Authorization") == "Bearer new-access") MockResponse().setBody("{}") else MockResponse().setResponseCode(401)
            }
        }
        x.dispatcher = answer
        y.dispatcher = answer
        val codes = CopyOnWriteArrayList<Int>()
        val threads = listOf(x, y).map { server -> Thread { get(signedInClient(server.url("/")), server).use { codes += it.code } } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(listOf(200, 200), codes.toList())
        assertEquals("one refresh for the account, not one per address", 1, refreshCalls.get())
        assertEquals("new-access", store.access)
        assertFalse(store.reauth.value)
    }

    @Test fun theRetrofitApis_carryTheAccountsToken() = runBlocking {
        val libraries = api.libraryApi(a.serverId).filterNotNull().first()
        libraries.libraries()
        assertEquals(listOf<String?>("Bearer a-access"), seenX)
    }
}
