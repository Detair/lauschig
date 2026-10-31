package io.wolftown.lauschig.connection

import app.cash.turbine.test
import io.wolftown.lauschig.connection.ConnectionState.Online
import io.wolftown.lauschig.connection.ConnectionState.Via
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddressResolverTest {
    private val local = "http://192.168.1.5:13378/".toHttpUrl()
    private val public = "https://abs.example.org/".toHttpUrl()

    private class FakeProbe(val reachable: MutableSet<HttpUrl> = mutableSetOf()) : ReachabilityProbe {
        val probed = mutableListOf<HttpUrl>()
        override suspend fun isReachable(url: HttpUrl): Boolean {
            probed += url
            return url in reachable
        }
    }

    private class FakeNet(connected: Boolean = true, var ssid: String? = null) : NetworkInfo {
        val flow = MutableStateFlow(connected)
        override val isConnected = flow
        override suspend fun currentSsid() = ssid
    }

    private fun resolver(p: FakeProbe, n: FakeNet) = AddressResolver(p, n)

    @Test
    fun noNetwork_isOffline_withoutProbing() = runTest {
        val probe = FakeProbe(mutableSetOf(local, public))
        val r = resolver(probe, FakeNet(connected = false))
        assertEquals(ConnectionState.Offline, r.resolve(ServerAddresses(local, public)))
        assertTrue(probe.probed.isEmpty())
    }

    @Test
    fun homeSsid_prefersLocal() = runTest {
        val probe = FakeProbe(mutableSetOf(local, public))
        val r = resolver(probe, FakeNet(ssid = "Home"))
        val s = r.resolve(ServerAddresses(local, public, setOf("Home")))
        assertEquals(Online(local, Via.LOCAL), s)
        assertEquals(listOf(local), probe.probed)
    }

    @Test
    fun foreignSsid_skipsLocal_usesPublic() = runTest {
        val probe = FakeProbe(mutableSetOf(local, public))
        val r = resolver(probe, FakeNet(ssid = "Cafe"))
        val s = r.resolve(ServerAddresses(local, public, setOf("Home")))
        assertEquals(Online(public, Via.PUBLIC), s)
        assertTrue(local !in probe.probed)
    }

    @Test
    fun unknownSsid_withHomeSsidsSet_skipsLocal() = runTest {
        val probe = FakeProbe(mutableSetOf(local, public))
        val r = resolver(probe, FakeNet(ssid = null))
        r.resolve(ServerAddresses(local, public, setOf("Home")))
        assertTrue(local !in probe.probed)
    }

    @Test
    fun localUnreachable_fallsBackToPublic_thenOffline() = runTest {
        val probe = FakeProbe(mutableSetOf(public))
        val r = resolver(probe, FakeNet())
        val a = ServerAddresses(local, public)
        assertEquals(Online(public, Via.PUBLIC), r.resolve(a))
        assertEquals(listOf(local, public), probe.probed)
        probe.reachable.clear()
        assertEquals(ConnectionState.Offline, r.resolve(a))
    }

    @Test
    fun onlyPublicConfigured_works() = runTest {
        val probe = FakeProbe(mutableSetOf(public))
        val r = resolver(probe, FakeNet())
        assertEquals(Online(public, Via.PUBLIC), r.resolve(ServerAddresses(null, public)))
        assertEquals(listOf(public), probe.probed)
    }

    @Test
    fun states_reResolvesOnNetworkChange_andEveryMinuteWhileOffline() = runTest {
        val probe = FakeProbe()
        val net = FakeNet(connected = false)
        val r = AddressResolver(probe, net)
        r.states(ServerAddresses(local, public)).test {
            assertEquals(ConnectionState.Offline, awaitItem())
            assertTrue(probe.probed.isEmpty())

            // Network appears but nothing is reachable: still offline, retried after 60 s.
            net.flow.value = true
            testScheduler.runCurrent()
            val afterFirst = probe.probed.size
            assertEquals(2, afterFirst)
            testScheduler.advanceTimeBy(59_000)
            testScheduler.runCurrent()
            assertEquals(afterFirst, probe.probed.size)
            probe.reachable += public
            testScheduler.advanceTimeBy(1_001)
            testScheduler.runCurrent()
            assertEquals(Online(public, Via.PUBLIC), awaitItem())

            // Online: no periodic re-check.
            val n = probe.probed.size
            testScheduler.advanceTimeBy(300_000)
            testScheduler.runCurrent()
            assertEquals(n, probe.probed.size)

            // Network drops, then returns: re-resolved.
            net.flow.value = false
            assertEquals(ConnectionState.Offline, awaitItem())
            probe.reachable += local
            net.flow.value = true
            assertEquals(Online(local, Via.LOCAL), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
