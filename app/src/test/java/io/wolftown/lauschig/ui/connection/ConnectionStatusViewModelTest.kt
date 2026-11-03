package io.wolftown.lauschig.ui.connection

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionLogState
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.LinkState
import io.wolftown.lauschig.connection.NetworkDescription
import io.wolftown.lauschig.connection.ProbeRecord
import io.wolftown.lauschig.connection.ReconnectState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.connection.SyncInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionStatusViewModelTest {
    private val viewModels = TestViewModels()
    private val public = "https://abs.example.org/".toHttpUrl()
    private val addresses = ServerAddresses(null, public)
    private val account = MutableStateFlow<Account?>(Account("0123456789abcdef", "u1", "fabian", true, addresses, emptySet()))
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    private val checking = MutableStateFlow(true)
    private val log = MutableStateFlow(ConnectionLogState())
    private val reconnected = mutableListOf<ServerAddresses>()
    private var probesOk = true
    private var online = CompletableDeferred<Boolean>()
    private var syncCalls = 0

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(
        ConnectionStatusViewModel(
            account, connection, checking, log,
            network = { NetworkDescription(setOf(NetworkDescription.Transport.CELLULAR), metered = true, validated = true) },
            ssid = { null },
            syncInfo = { syncCalls++; SyncInfo(5_000, 3, 0) },
            reconnectAll = { reconnected += it; listOf(ProbeRecord(public, probesOk, 10, 0)) },
            waitOnline = { online.await() },
        ),
    )

    @Test fun mapsTheSharedState_networkAndSync() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(LinkState.CHECKING, vm.state.value.state)
        checking.value = false
        assertEquals(LinkState.OFFLINE, vm.state.value.state)
        connection.value = ConnectionState.Online(public, ConnectionState.Via.PUBLIC)
        val s = vm.state.value
        assertEquals(LinkState.PUBLIC, s.state)
        assertEquals(public, s.baseUrl)
        assertTrue(s.addresses.single().inUse)
        assertEquals(setOf(NetworkDescription.Transport.CELLULAR), s.network!!.transports)
        assertEquals(3, s.sync!!.dirtyProgress)
    }

    @Test fun reconnect_showsProgress_thenTheResult_andRefreshes() = runTest {
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        checking.value = false
        val before = syncCalls
        vm.reconnect()
        assertEquals(listOf(addresses), reconnected)
        assertEquals(ReconnectState.Running, vm.state.value.reconnect)
        assertEquals(LinkState.CHECKING, vm.state.value.state)
        vm.reconnect() // a second tap while running does nothing
        assertEquals(1, reconnected.size)
        online.complete(false)
        assertEquals(ReconnectState.Done(online = false), vm.state.value.reconnect)
        assertEquals(LinkState.OFFLINE, vm.state.value.state)
        assertTrue(syncCalls > before)

        online = CompletableDeferred(true)
        connection.value = ConnectionState.Online(public, ConnectionState.Via.PUBLIC)
        vm.reconnect()
        assertEquals(ReconnectState.Done(online = true), vm.state.value.reconnect)
    }

    @Test fun reconnect_withoutAccount_doesNothing() = runTest {
        account.value = null
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.reconnect()
        assertTrue(reconnected.isEmpty())
        assertEquals(null, vm.state.value.reconnect)
    }

    @Test fun reconnect_everyProbeFailed_isNotConnected_evenIfTheStateStillSaysOnline() = runTest {
        // An old Online (the shared state keeps it until the confirmation probes ran) must not read as "Verbunden".
        connection.value = ConnectionState.Online(public, ConnectionState.Via.PUBLIC)
        checking.value = false
        probesOk = false
        online = CompletableDeferred(true)
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.reconnect()
        assertEquals(ReconnectState.Done(online = false), vm.state.value.reconnect)
    }
}
