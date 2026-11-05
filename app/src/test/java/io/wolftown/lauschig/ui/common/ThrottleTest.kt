package io.wolftown.lauschig.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {
    /** (structure, position): the structure is the key, the position ticks like a playback write. */
    private data class Row(val structure: String, val pos: Int)

    @Test fun ticks_areThrottledToOnePerWindow_userChangesPassAtOnce() = runTest {
        val src = MutableSharedFlow<Row>()
        val got = mutableListOf<Row>()
        backgroundScope.launch { src.throttleUnchangedKey(1_500) { it.structure }.collect { got += it } }
        runCurrent()

        src.emit(Row("playing", 0)); runCurrent()
        assertEquals(listOf(Row("playing", 0)), got) // first value: immediately
        // 12 position writes, one per second.
        for (t in 1..12) { advanceTimeBy(1_000); src.emit(Row("playing", t)); runCurrent() }
        // 12 s of ticks: at most one emission per window, 5 here (before: 12).
        assertEquals(true, got.size - 1 in 4..6)
        assertEquals(got.sortedBy { it.pos }, got) // never out of order

        // "Mark finished": different structure, no waiting.
        src.emit(Row("finished", 13)); runCurrent()
        assertEquals(Row("finished", 13), got.last())

        // The newest held tick is always delivered when its window ends.
        val before = got.size
        src.emit(Row("finished", 14)); src.emit(Row("finished", 15)); runCurrent()
        assertEquals(before, got.size)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(Row("finished", 15), got.last())
        assertEquals(before + 1, got.size)
    }

    @Test fun userChange_dropsAHeldTickOfTheOldStructure() = runTest {
        val src = MutableSharedFlow<Row>()
        val got = mutableListOf<Row>()
        backgroundScope.launch { src.throttleUnchangedKey(1_500) { it.structure }.collect { got += it } }
        runCurrent()
        src.emit(Row("a", 0)); runCurrent()
        src.emit(Row("a", 1)); runCurrent() // held
        src.emit(Row("b", 2)); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(Row("a", 0), Row("b", 2)), got)
    }

    @Test fun heldTick_thenNewKeyAtTheWindowEnd_arrivesInOrder() = runTest {
        val src = MutableSharedFlow<Row>()
        val got = mutableListOf<Row>()
        backgroundScope.launch { src.throttleUnchangedKey(1_500) { it.structure }.collect { got += it } }
        runCurrent()
        src.emit(Row("a", 0)); runCurrent()
        src.emit(Row("a", 1)); runCurrent() // held, released at t=1500
        advanceTimeBy(1_499); runCurrent()
        src.emit(Row("b", 2)); runCurrent() // new key right before the release
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf(Row("a", 0), Row("b", 2)), got) // the old tick never follows the newer value
        assertEquals(got.sortedBy { it.pos }, got)
    }
}
