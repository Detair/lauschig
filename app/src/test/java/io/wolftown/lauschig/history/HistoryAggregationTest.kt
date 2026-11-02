package io.wolftown.lauschig.history

import io.wolftown.lauschig.db.entities.HistorySessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAggregationTest {
    private val s = "srv"
    private fun server(id: String, started: Long, listened: Double, end: Double = 100.0, device: String? = "Web") =
        HistorySessionEntity(s, id, "k", started, started + 1, 0.0, end, listened, device)
    private fun local(id: String, started: Long, listened: Double, synced: Boolean, end: Double = 200.0) =
        LocalSessionEntity(id, s, "k", 10.0, end, listened, started, started + 1, synced)

    @Test fun mergesDedupesAndSortsNewestFirst() {
        val h = HistoryAggregation.build(
            server = listOf(server("a", 100, 60.0), server("b", 300, 30.0)),
            local = listOf(local("c", 200, 40.0, synced = false)),
        )
        assertEquals(listOf("b", "c", "a"), h.entries.map { it.id })
        assertEquals(130.0, h.totalListenedSec, 0.0)
        assertTrue(h.entries[1].thisDevice)
        assertFalse(h.entries[0].thisDevice)
    }

    @Test fun unsyncedLocalWinsOverServerCopy_syncedServerCopyWins() {
        val h = HistoryAggregation.build(
            server = listOf(server("x", 100, 10.0, end = 50.0), server("y", 200, 20.0, end = 70.0, device = "Pixel")),
            local = listOf(local("x", 100, 99.0, synced = false, end = 300.0), local("y", 200, 21.0, synced = true, end = 71.0)),
        )
        val x = h.entries.first { it.id == "x" }
        assertEquals(99.0, x.listenedSec, 0.0)
        assertEquals(300.0, x.endSec, 0.0)
        assertTrue(x.thisDevice)
        val y = h.entries.first { it.id == "y" }
        assertEquals(20.0, y.listenedSec, 0.0)
        assertEquals("Pixel", y.deviceName)
        assertTrue(y.thisDevice)
        assertEquals(2, h.entries.size)
    }

    @Test fun shortSessionsAreHidden_andNotCounted() {
        val h = HistoryAggregation.build(server = listOf(server("a", 1, 4.9), server("b", 2, 5.0)), local = emptyList())
        assertEquals(listOf("b"), h.entries.map { it.id })
        assertEquals(5.0, h.totalListenedSec, 0.0)
    }

    @Test fun empty() {
        val h = HistoryAggregation.build(emptyList(), emptyList())
        assertTrue(h.entries.isEmpty())
        assertEquals(0.0, h.totalListenedSec, 0.0)
    }
}
