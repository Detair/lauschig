package io.wolftown.lauschig.queue

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAdvanceTest {
    private val all: (String) -> Boolean = { true }

    @Test fun emptyQueue_nothing() {
        assertNull(AdvancePolicy.next(emptyList(), "a", all, sleepEndOfChapter = false))
    }

    @Test fun takesFirst_skippingTheEndedTitle() {
        assertEquals("b", AdvancePolicy.next(listOf("a", "b", "c"), "a", all, false))
        assertEquals("b", AdvancePolicy.next(listOf("b", "c"), "a", all, false))
        assertNull(AdvancePolicy.next(listOf("a"), "a", all, false))
    }

    @Test fun skipsUnplayable() {
        assertEquals("c", AdvancePolicy.next(listOf("b", "c"), "a", { it == "c" }, false))
        assertNull(AdvancePolicy.next(listOf("b"), "a", { false }, false))
    }

    @Test fun sleepTimerAtEndOfChapter_blocks() {
        assertNull(AdvancePolicy.next(listOf("b"), "a", all, sleepEndOfChapter = true))
    }

    @Test fun advancer_playsNext_removesEndedAndStarted_keepsUnplayable() = runTest {
        val queue = mutableListOf("a", "x", "b", "c")
        val played = mutableListOf<String>()
        val advancer = QueueAdvancer(
            serverId = { "srv" },
            entries = { queue.toList() },
            remove = { _, k -> queue.remove(k) },
            playable = { _, k -> k != "x" },
            sleepEndOfChapter = { false },
            play = { _, k -> played += k },
        )
        advancer.onEnded("a")
        assertEquals(listOf("b"), played)
        assertEquals(listOf("x", "c"), queue)
    }

    @Test fun advancer_withoutPlayableEntry_onlyDropsTheEndedTitle() = runTest {
        val queue = mutableListOf("a", "x")
        val played = mutableListOf<String>()
        val advancer = QueueAdvancer({ "srv" }, { queue.toList() }, { _, k -> queue.remove(k) }, { _, _ -> false }, { false }, { _, k -> played += k })
        advancer.onEnded("a")
        assertTrue(played.isEmpty())
        assertEquals(listOf("x"), queue)
    }
}
