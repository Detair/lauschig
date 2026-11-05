package io.wolftown.lauschig.connection

import app.cash.turbine.test
import io.wolftown.lauschig.connection.ConnectionState.Online
import io.wolftown.lauschig.connection.ConnectionState.Via
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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

    // Task 1.6: the server of a saved profile; the Verbindungsstatus window stays the signed-in account's.
    @Test
    fun resolveElsewhere_decidesLikeResolve_butLeavesTheWindowAlone() = runTest {
        val log = ConnectionLog()
        val probe = FakeProbe(mutableSetOf(public))
        val r = AddressResolver(probe, FakeNet(), log = log)
        assertEquals(ConnectionState.Online(public, ConnectionState.Via.PUBLIC), r.resolveElsewhere(ServerAddresses(local, public)))
        assertEquals(listOf(local, public), probe.probed)
        assertEquals(ConnectionLogState(), log.state.value)
    }

    @Test
    fun resolveElsewhere_noNetwork_isOffline_withoutProbing() = runTest {
        val probe = FakeProbe(mutableSetOf(local))
        assertEquals(ConnectionState.Offline, resolver(probe, FakeNet(connected = false)).resolveElsewhere(ServerAddresses(local, null)))
        assertTrue(probe.probed.isEmpty())
    }

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
    fun states_reResolvesOnNetworkChange_andWithBackoffWhileOffline() = runTest {
        val probe = FakeProbe()
        val net = FakeNet(connected = false)
        val r = AddressResolver(probe, net)
        r.states(ServerAddresses(local, public)).test {
            assertEquals(ConnectionState.Offline, awaitItem())
            assertTrue(probe.probed.isEmpty())

            // Network appears but nothing is reachable: still offline, retried after 5 s, then 10 s.
            net.flow.value = true
            testScheduler.runCurrent()
            assertEquals(2, probe.probed.size)
            testScheduler.advanceTimeBy(4_999)
            testScheduler.runCurrent()
            assertEquals(2, probe.probed.size)
            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()
            assertEquals(4, probe.probed.size)
            testScheduler.advanceTimeBy(9_990)
            testScheduler.runCurrent()
            assertEquals(4, probe.probed.size)
            probe.reachable += public
            testScheduler.advanceTimeBy(11)
            testScheduler.runCurrent()
            assertEquals(Online(public, Via.PUBLIC), awaitItem())

            // Online: no periodic re-check.
            val n = probe.probed.size
            testScheduler.advanceTimeBy(300_000)
            testScheduler.runCurrent()
            assertEquals(n, probe.probed.size)

            // Network drops (no confirmation needed without a network), then returns: re-resolved.
            net.flow.value = false
            assertEquals(ConnectionState.Offline, awaitItem())
            probe.reachable += local
            net.flow.value = true
            assertEquals(Online(local, Via.LOCAL), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Emits every network event, also a repeated `true` (a switch between two networks). */
    private class EventNet : NetworkInfo {
        val events = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(true) }
        override val isConnected = events
        override suspend fun currentSsid(): String? = null
    }

    @Test
    fun states_singleFailedProbeWhileOnline_doesNotGoOffline() = runTest {
        val probe = FakeProbe(mutableSetOf(public))
        val net = EventNet()
        AddressResolver(probe, net).states(ServerAddresses(null, public)).test {
            assertEquals(Online(public, Via.PUBLIC), awaitItem())
            // A network event while the link is saturated: the probe fails once, the confirmation succeeds.
            probe.reachable.clear()
            net.events.emit(true)
            testScheduler.runCurrent()
            assertEquals(2, probe.probed.size)
            probe.reachable += public
            testScheduler.advanceTimeBy(2_001)
            testScheduler.runCurrent()
            assertEquals(3, probe.probed.size)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun states_serverGoneWithNetworkUp_offlineAfterTwoConfirmations() = runTest {
        val probe = FakeProbe(mutableSetOf(public))
        val net = EventNet()
        AddressResolver(probe, net).states(ServerAddresses(null, public)).test {
            assertEquals(Online(public, Via.PUBLIC), awaitItem())
            probe.reachable.clear()
            net.events.emit(true)
            testScheduler.advanceTimeBy(3_999)
            testScheduler.runCurrent()
            expectNoEvents()
            testScheduler.advanceTimeBy(2)
            testScheduler.runCurrent()
            assertEquals(ConnectionState.Offline, awaitItem())
            assertEquals(4, probe.probed.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Verbindungsstatus (phase 4B) ---

    private class FailingProbe(val reachable: MutableSet<HttpUrl> = mutableSetOf()) : ReachabilityProbe {
        override suspend fun isReachable(url: HttpUrl) = url in reachable
        override suspend fun check(url: HttpUrl) = if (url in reachable) ProbeOutcome(true) else ProbeOutcome(false, "SocketTimeoutException: connect timed out")
    }

    @Test
    fun resolve_writesEveryProbeAndTheDecision_toTheLog() = runTest {
        val log = ConnectionLog()
        var now = 1_000L
        val r = AddressResolver(FailingProbe(mutableSetOf(public)), FakeNet(), log = log, clock = { now++ })
        assertEquals(Online(public, Via.PUBLIC), r.resolve(ServerAddresses(local, public)))
        val s = log.state.value
        assertEquals(false, s.probes[local.toString()]!!.ok)
        assertEquals("SocketTimeoutException: connect timed out", s.probes[local.toString()]!!.error)
        assertEquals(true, s.probes[public.toString()]!!.ok)
        assertEquals(Online(public, Via.PUBLIC), s.resolved)
        assertEquals(public, s.lastSuccess!!.url)
        assertEquals(local, s.lastFailure!!.url)
    }

    @Test
    fun resolve_withoutNetwork_logsNoNetwork() = runTest {
        val log = ConnectionLog()
        AddressResolver(FakeProbe(), FakeNet(connected = false), log = log).resolve(ServerAddresses(local, public))
        assertTrue(log.state.value.noNetwork)
        assertTrue(log.state.value.probes.isEmpty())
    }

    @Test
    fun reconnect_probesEveryAddress_alsoTheLocalOneOffTheHomeWifi() = runTest {
        val probe = FakeProbe(mutableSetOf(public))
        val log = ConnectionLog()
        val r = AddressResolver(probe, FakeNet(ssid = "Cafe"), log = log)
        val results = r.reconnect(ServerAddresses(local, public, setOf("Home")))
        assertEquals(setOf(local, public), probe.probed.toSet())
        assertEquals(listOf(false, true), results.map { it.ok })
        assertEquals(2, log.state.value.probes.size)
    }

    @Test
    fun recheck_whileOffline_probesAgainAtOnce() = runTest {
        val probe = FakeProbe()
        val r = AddressResolver(probe, FakeNet())
        r.states(ServerAddresses(null, public)).test {
            assertEquals(ConnectionState.Offline, awaitItem())
            assertEquals(1, probe.probed.size)
            // The next offline re-check would come after 5 s; "Neu verbinden" must not wait for it.
            probe.reachable += public
            r.recheck()
            testScheduler.runCurrent()
            assertEquals(2, probe.probed.size)
            assertEquals(Online(public, Via.PUBLIC), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
