package io.wolftown.lauschig.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
