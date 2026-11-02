package io.wolftown.lauschig.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.queue.QueueEntryState.MISSING
import io.wolftown.lauschig.queue.QueueEntryState.PLAYABLE
import io.wolftown.lauschig.queue.QueueEntryState.UNAVAILABLE_NOW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueAdvanceTest {
    private val s = "srv"

    @Test fun classify() {
        assertEquals(MISSING, AdvancePolicy.classify(exists = false, orphaned = false, downloaded = true, online = true))
        assertEquals(MISSING, AdvancePolicy.classify(exists = true, orphaned = true, downloaded = false, online = true))
        assertEquals(PLAYABLE, AdvancePolicy.classify(exists = true, orphaned = true, downloaded = true, online = false))
        assertEquals(PLAYABLE, AdvancePolicy.classify(exists = true, orphaned = false, downloaded = false, online = true))
        assertEquals(UNAVAILABLE_NOW, AdvancePolicy.classify(exists = true, orphaned = false, downloaded = false, online = false))
    }

    @Test fun queueEntryState_fromRoom() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.itemDao().upsertFromList(listOf(ItemEntity(s, "b", "l", "B"), ItemEntity(s, "pod", "l", "P")))
            db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "e", "E")))
            db.downloadDao().upsert(DownloadEntity(s, "pod~e", DownloadState.COMPLETE, 1))
            fun state(key: String, online: Boolean) = kotlinx.coroutines.runBlocking { queueEntryState(db.itemDao(), db.episodeDao(), db.downloadDao(), online, s, key) }
            assertEquals(UNAVAILABLE_NOW, state("b", online = false))
            assertEquals(PLAYABLE, state("pod~e", online = false))
            assertEquals(MISSING, state("pod~gone", online = true))
            assertEquals(MISSING, state("gone", online = true))
        } finally {
            db.close()
        }
    }

    private class Run(val queue: MutableList<String>, states: Map<String, QueueEntryState>, failing: Set<String> = emptySet(), sleep: Boolean = false) {
        val started = mutableListOf<String>()
        val advancer = QueueAdvancer(
            entries = { queue.toList() },
            remove = { _, k -> queue.remove(k) },
            check = { _, k -> states[k] ?: PLAYABLE },
            sleepEndOfChapter = { sleep },
            start = { _, k -> started += k; k !in failing },
        )
    }

    @Test fun startsFirstPlayable_removesEndedAndStarted() = runTest {
        val r = Run(mutableListOf("a", "b", "c"), emptyMap())
        assertEquals("b", r.advancer.onEnded(s, "a"))
        assertEquals(listOf("c"), r.queue)
    }

    @Test fun missingIsDropped_unavailableStays_failedStartIsDroppedAndTheNextTried() = runTest {
        val r = Run(mutableListOf("gone", "offline", "broken", "ok", "later"), mapOf("gone" to MISSING, "offline" to UNAVAILABLE_NOW), failing = setOf("broken"))
        assertEquals("ok", r.advancer.onEnded(s, "x"))
        assertEquals(listOf("broken", "ok"), r.started)
        assertEquals(listOf("offline", "later"), r.queue)
    }

    @Test fun nothingPlayable_returnsNull_andOnlyDropsEndedAndMissing() = runTest {
        val r = Run(mutableListOf("a", "offline", "gone"), mapOf("offline" to UNAVAILABLE_NOW, "gone" to MISSING))
        assertNull(r.advancer.onEnded(s, "a"))
        assertEquals(listOf("offline"), r.queue)
        assertTrue(r.started.isEmpty())
    }

    @Test fun sleepTimerAtEndOfChapter_startsNothing() = runTest {
        val r = Run(mutableListOf("a", "b"), emptyMap(), sleep = true)
        assertNull(r.advancer.onEnded(s, "a"))
        assertEquals(listOf("b"), r.queue)
    }
}
