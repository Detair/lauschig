package io.wolftown.lauschig.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetPublisherTest {
    private class FakeStore(initial: WidgetState? = null) : WidgetStore {
        val flow = MutableStateFlow(initial)
        val writes = mutableListOf<WidgetState>()
        override val state = flow
        override suspend fun set(state: WidgetState) {
            writes += state
            flow.value = state.takeIf { it.hasTitle }
        }
    }

    private val empty = WidgetPlayerSnapshot(null, null, null, null, null, 0, false, 1, 0, 0, emptyList(), -1)
    private fun playing(positionMs: Long, play: Boolean = true) =
        WidgetPlayerSnapshot("0123456789abcdef", "li_1", "Titel", "Kapitel 1", "Autorin", 0, play, 3, 0, positionMs, listOf(600_000), 600_000)

    private var snap = empty
    private var refreshes = 0

    private fun TestScope.publisher(store: FakeStore) = WidgetPublisher(
        snapshot = { snap },
        hasItems = { snap.key != null },
        scope = backgroundScope,
        io = backgroundScope,
        store = store,
        refresh = { refreshes++ },
        clock = { testScheduler.currentTime },
    )

    @Test fun start_correctsAStoredPlaying_toPaused() = runTest {
        // A process death left "playing" in the store; the service starts with an empty player.
        val stale = WidgetState("0123456789abcdef", "li_1", "Titel", null, 0, playing = true, positionMs = 5, durationMs = 9)
        val store = FakeStore(stale)
        publisher(store).start()
        runCurrent()
        assertEquals(stale.copy(playing = false), store.flow.value)
        assertEquals(1, refreshes)
    }

    @Test fun changesAtOnce_positionEvery30s_whilePlaying_notWhenPaused() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        p.start()
        runCurrent()
        snap = playing(0)
        p.changed()
        runCurrent()
        assertEquals(0L, store.flow.value!!.positionMs)
        val writes = store.writes.size

        snap = playing(10_000)
        p.changed() // a position-only change: waits for the 30 s tick
        runCurrent()
        assertEquals(writes, store.writes.size)
        snap = playing(30_000)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(30_000L, store.flow.value!!.positionMs)
        assertTrue(store.flow.value!!.playing)

        snap = playing(31_000, play = false)
        p.changed()
        runCurrent()
        assertFalse(store.flow.value!!.playing)
        assertEquals(31_000L, store.flow.value!!.positionMs)
        // Paused: no ticking any more.
        val afterPause = store.writes.size
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(afterPause, store.writes.size)
    }

    @Test fun release_leavesTheTitlePaused_atItsLatestPosition() = runTest {
        val store = FakeStore()
        val p = publisher(store)
        p.start()
        snap = playing(5_000)
        p.changed()
        runCurrent()
        snap = playing(12_000)
        p.release()
        runCurrent()
        val last = store.flow.value!!
        assertFalse(last.playing)
        assertEquals(12_000L, last.positionMs)
        assertEquals("li_1", last.key)
    }
}
