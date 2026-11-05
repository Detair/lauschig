package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiProviderTest {
    @Test
    fun twoCollectors_causeSingleUpstreamSubscription() = runTest(UnconfinedTestDispatcher()) {
        var subscriptions = 0
        val upstream = flow {
            subscriptions++
            emit(ConnectionState.Online("http://abs.test/".toHttpUrl(), ConnectionState.Via.PUBLIC))
            awaitCancellation()
        }
        val provider = ApiProvider(upstream, OkHttpClient(), FakeAccountStore().apply { acc.value = a }, backgroundScope)
        var auth: AuthApi? = null
        var lib: LibraryApi? = null
        backgroundScope.launch { provider.authApi.collect { auth = it } }
        backgroundScope.launch { provider.libraryApi(a.serverId).collect { lib = it } }
        assertEquals(1, subscriptions)
        assertNotNull(auth)
        assertNotNull(lib)
    }

    @Test
    fun afterNobodyListened_theStateIsOfflineAgain_untilAFreshProbeAnswers() = runTest {
        // At home the state was Online(LOCAL). The process idled (nobody collected), then the car started the
        // service: an old Online must not be replayed as if it were current (it would stream from the home address).
        val home = ConnectionState.Online("http://192.168.1.10:13378/".toHttpUrl(), ConnectionState.Via.LOCAL)
        val upstream = flow {
            emit(home)
            awaitCancellation()
        }
        val provider = ApiProvider(upstream, OkHttpClient(), FakeAccountStore(), backgroundScope)
        val first = backgroundScope.launch { provider.connection.collect {} }
        runCurrent()
        assertEquals(home, provider.connectionState.value)
        first.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(ConnectionState.Offline, provider.connectionState.value)
    }

    @Test
    fun checking_untilTheFirstAnswer_andAgainAfterTheListenersLeft() = runTest {
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        val upstream = flow {
            answer.await()
            emit(ConnectionState.Offline)
            awaitCancellation()
        }
        val provider = ApiProvider(upstream, OkHttpClient(), FakeAccountStore(), backgroundScope)
        assertEquals(true, provider.checking.value)
        val first = backgroundScope.launch { provider.connection.collect {} }
        runCurrent()
        // Offline is only the initial value here: the probe has not answered yet.
        assertEquals(true, provider.checking.value)
        answer.complete(Unit)
        runCurrent()
        assertEquals(false, provider.checking.value)
        first.cancel()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(true, provider.checking.value)
    }

    private val a = Account("aaaaaaaaaaaaaaaa", "u1", "fabian", false, ServerAddresses("http://abs.test/".toHttpUrl(), null), emptySet())
    private val b = a.copy(serverId = "bbbbbbbbbbbbbbbb", userId = "u2", username = "lukas")

    /** Family case: one server, so the connection does not change at a switch, or the shared state skips its reset. */
    @Test
    fun aCollectorThatStays_getsTheNewAccountsClient_withoutAConnectionReset() = runTest(UnconfinedTestDispatcher()) {
        val upstream = flow {
            emit(ConnectionState.Online("http://abs.test/".toHttpUrl(), ConnectionState.Via.LOCAL))
            awaitCancellation()
        }
        val store = FakeAccountStore().apply { acc.value = a }
        val provider = ApiProvider(upstream, OkHttpClient(), store, backgroundScope)
        val clients = mutableListOf<AuthApi>()
        backgroundScope.launch { provider.authApi.collect { if (it != null) clients += it } }
        assertEquals(1, clients.size)
        store.acc.value = b
        assertEquals(2, clients.size)
        assertNotSame(clients[0], clients[1])
    }

    @Test
    fun theApiOfAnAccount_isThereOnlyWhileItIsTheSignedInOne() = runTest(UnconfinedTestDispatcher()) {
        val upstream = flow {
            emit(ConnectionState.Online("http://abs.test/".toHttpUrl(), ConnectionState.Via.LOCAL))
            awaitCancellation()
        }
        val store = FakeAccountStore().apply { acc.value = a }
        val provider = ApiProvider(upstream, OkHttpClient(), store, backgroundScope)
        var ofA: MeApi? = null
        var ofB: LibraryApi? = null
        var admin: AdminApi? = null
        backgroundScope.launch { provider.meApi(a.serverId).collect { ofA = it } }
        backgroundScope.launch { provider.libraryApi(b.serverId).collect { ofB = it } }
        backgroundScope.launch { provider.adminApi(a.serverId).collect { admin = it } }
        assertNotNull(ofA)
        assertNull(ofB)
        assertNotNull(admin)
        store.acc.value = b
        assertNull(ofA)
        assertNotNull(ofB)
        assertNull(admin)
        store.acc.value = null
        assertNull(ofA)
        assertNull(ofB)
    }

    @Test
    fun aReset_meansOfflineAndChecking_untilTheNewServerAnswers() = runTest {
        // Account switch A → B: B's token must never go to A's host while B's first probe runs.
        val a = ConnectionState.Online("https://a.example/".toHttpUrl(), ConnectionState.Via.PUBLIC)
        val b = ConnectionState.Online("https://b.example/".toHttpUrl(), ConnectionState.Via.PUBLIC)
        val upstream = flow<ConnectionState?> {
            emit(a)
            kotlinx.coroutines.delay(1_000)
            emit(null) // the reset of a new server
            kotlinx.coroutines.delay(1_000)
            emit(b)
            awaitCancellation()
        }
        val provider = ApiProvider(upstream, OkHttpClient(), FakeAccountStore(), backgroundScope)
        backgroundScope.launch { provider.connection.collect {} }
        runCurrent()
        assertEquals(a, provider.connectionState.value)
        assertEquals(false, provider.checking.value)
        advanceTimeBy(1_001)
        assertEquals(ConnectionState.Offline, provider.connectionState.value)
        assertEquals(true, provider.checking.value)
        advanceTimeBy(1_000)
        assertEquals(b, provider.connectionState.value)
        assertEquals(false, provider.checking.value)
    }
}
