package io.wolftown.lauschig.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

class WidgetAccountWatcherTest {
    private class FakeStore(initial: WidgetState?) : WidgetStore {
        val flow = MutableStateFlow(initial)
        override val state = flow
        override suspend fun set(state: WidgetState) { flow.value = state.takeIf { it.hasTitle } }
    }

    private val title = WidgetState("0123456789abcdef", "li_1", "Titel", null, 0, playing = false, positionMs = 1, durationMs = 2)

    @Test fun signOut_clearsTheStoredTitle() = runTest {
        val store = FakeStore(title)
        assertTrue(WidgetAccountWatcher.clearIfForeign(store, accountServerId = null))
        assertEquals(null, store.state.first())
    }

    @Test fun anotherServer_clearsIt_theSameAccountKeepsIt() = runTest {
        val store = FakeStore(title)
        assertFalse(WidgetAccountWatcher.clearIfForeign(store, accountServerId = "0123456789abcdef"))
        assertEquals(title, store.state.first())
        assertTrue(WidgetAccountWatcher.clearIfForeign(store, accountServerId = "fedcba9876543210"))
        assertEquals(null, store.state.first())
    }

    @Test fun aDeviceTitle_staysAfterSignOut() = runTest {
        val device = title.copy(serverId = "device", key = "local:0123456789abcdef01234567")
        val store = FakeStore(device)
        assertFalse(WidgetAccountWatcher.clearIfForeign(store, accountServerId = null))
        assertEquals(device, store.state.first())
    }

    // Task 1.7: the widget follows a switch, also when there is nothing to clear.

    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private fun account(id: String) = io.wolftown.lauschig.account.Account(id, "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet())

    @Test fun aSwitch_updatesTheWidget_alsoWithNothingStored_andAlsoOnTheWayBack() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(a) }
        var updates = 0
        val watcher = WidgetAccountWatcher(accounts, FakeStore(null)) { updates++ }
        backgroundScope.launch { watcher.run() }
        assertEquals("the start draws nothing new", 0, updates)
        accounts.acc.value = account(b)
        assertEquals(1, updates)
        accounts.acc.value = account(a)
        assertEquals(2, updates)
    }

    @Test fun anotherUpdateOfTheSameAccount_doesNotRedrawIt() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(a) }
        var updates = 0
        backgroundScope.launch { WidgetAccountWatcher(accounts, FakeStore(null)) { updates++ }.run() }
        accounts.acc.value = account(a).copy(username = "neu")
        assertEquals(0, updates)
    }

    @Test fun atTheStart_aForeignTitle_isClearedAndRedrawn_asBefore() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(b) }
        val store = FakeStore(title)
        var updates = 0
        backgroundScope.launch { WidgetAccountWatcher(accounts, store) { updates++ }.run() }
        assertEquals(1, updates)
        assertEquals(null, store.state.first())
    }

    /** Review I2: a device title in the store is the left account's; the next one's own last title shows instead. */
    @Test fun aSwitch_aDeviceTitleOfTheLeftAccount_goesToo() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val device = title.copy(serverId = "device", key = "local:0123456789abcdef01234567")
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(a) }
        val store = FakeStore(device)
        backgroundScope.launch { WidgetAccountWatcher(accounts, store) {}.run() }
        assertEquals("the start keeps it", device, store.state.first())
        accounts.acc.value = account(b)
        assertEquals(null, store.state.first())
    }

    @Test fun aSwitch_aTitleOfTheNewAccount_stays() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val own = title.copy(serverId = b)
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(a) }
        val store = FakeStore(own)
        backgroundScope.launch { WidgetAccountWatcher(accounts, store) {}.run() }
        assertEquals("not A's: it is cleared at the start, as before", null, store.state.first())
        store.flow.value = own
        accounts.acc.value = account(b)
        assertEquals(own, store.state.first())
    }

    @Test fun aSignOutAndASignIn_keepADeviceTitle_asBefore() = runTest(kotlinx.coroutines.test.UnconfinedTestDispatcher()) {
        val device = title.copy(serverId = "device", key = "local:0123456789abcdef01234567")
        val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply { acc.value = account(a) }
        val store = FakeStore(device)
        backgroundScope.launch { WidgetAccountWatcher(accounts, store) {}.run() }
        accounts.acc.value = null
        assertEquals(device, store.state.first())
        accounts.acc.value = account(b)
        assertEquals(device, store.state.first())
    }
}
