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

    @Test fun queueOwner_onlyTheSignedInAccount() {
        val a = "aaaaaaaaaaaaaaaa"
        assertEquals(a, queueOwner(a, a))
        assertEquals(a, queueOwner(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, a)) // a device title in a's queue
        assertNull(queueOwner(a, null)) // signed out meanwhile
        assertNull(queueOwner(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, null))
        assertNull(queueOwner(a, "bbbbbbbbbbbbbbbb")) // another account now
    }

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

    private class Run(val queue: MutableList<String>, states: Map<String, QueueEntryState>, failing: Set<String> = emptySet(), sleep: Boolean = false, owner: Boolean = true) {
        val started = mutableListOf<String>()
        val advancer = QueueAdvancer(
            entries = { queue.toList() },
            remove = { _, k -> queue.remove(k) },
            check = { _, k -> states[k] ?: PLAYABLE },
            sleepEndOfChapter = { sleep },
            start = { _, k -> started += k; k !in failing },
            stillOwner = { owner },
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

    @Test fun signedOutMeanwhile_startsNothing_andKeepsTheQueue() = runTest {
        val r = Run(mutableListOf("a", "b", "c"), emptyMap(), owner = false)
        assertNull(r.advancer.onEnded(s, "a"))
        assertTrue(r.started.isEmpty())
        assertEquals(listOf("b", "c"), r.queue) // kept data stays as it was (only the ended title left)
    }

    // Review N1: the entry leaves only once the start succeeded; a switch during the start leaves it for the account's return.

    @Test fun anEntryIsStillQueuedWhileItsStartRuns() = runTest {
        val queue = mutableListOf("ended", "next")
        var queuedDuringStart: List<String>? = null
        val advancer = QueueAdvancer(
            entries = { _ -> queue.toList() },
            remove = { _, k -> queue.remove(k) },
            check = { _, _ -> QueueEntryState.PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, _ -> queuedDuringStart = queue.toList(); true },
        )
        assertEquals("next", advancer.onEnded("s", "ended"))
        assertEquals(listOf("next"), queuedDuringStart)
        assertEquals(emptyList<String>(), queue.toList())
    }

    @Test fun aSwitchDuringTheStart_leavesTheEntry_andTriesNoOther() = runTest {
        val queue = mutableListOf("ended", "next", "after")
        var owner = true
        val tried = mutableListOf<String>()
        val advancer = QueueAdvancer(
            entries = { _ -> queue.toList() },
            remove = { _, k -> queue.remove(k) },
            check = { _, _ -> QueueEntryState.PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, k -> tried += k; owner = false; false },
            stillOwner = { owner },
        )
        assertEquals(null, advancer.onEnded("s", "ended"))
        assertEquals(listOf("next"), tried)
        assertEquals(listOf("next", "after"), queue.toList())
    }

    // A start can throw, not only return false: a revoked folder, a database error, or after setQueue already started it.

    private class Throwing(val queue: MutableList<String>, var owner: Boolean = true, val failure: Exception = java.io.IOException("gone")) {
        val started = mutableListOf<String>()
        val advancer = QueueAdvancer(
            entries = { queue.toList() },
            remove = { _, k -> queue.remove(k) },
            check = { _, _ -> PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, k ->
                started += k
                if (k == "bad") throw failure
                true
            },
            stillOwner = { owner },
        )
    }

    @Test fun aStartThatThrows_doesNotBlockTheQueue_theNextOneStarts() = runTest {
        val r = Throwing(mutableListOf("a", "bad", "c"))
        assertEquals("c", r.advancer.onEnded(s, "a"))
        assertEquals(listOf("bad", "c"), r.started)
        assertEquals(emptyList<String>(), r.queue)
    }

    /** It may have started before it threw: it must not start a second time at the next end. */
    @Test fun aStartThatThrows_isNotTriedAgain() = runTest {
        val r = Throwing(mutableListOf("bad"))
        assertNull(r.advancer.onEnded(s, "a"))
        assertNull(r.advancer.onEnded(s, "bad"))
        assertEquals(listOf("bad"), r.started)
        assertEquals(emptyList<String>(), r.queue)
    }

    /** The same rule as a start that returns false: the account was left meanwhile, so the entry waits for its return. */
    @Test fun aStartThatThrows_afterTheAccountWasLeft_leavesTheEntry_andTriesNoOther() = runTest {
        val r = Throwing(mutableListOf("bad", "c"))
        val leaving = QueueAdvancer(
            entries = { r.queue.toList() },
            remove = { _, k -> r.queue.remove(k) },
            check = { _, _ -> PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, k ->
                r.started += k
                r.owner = false
                throw java.io.IOException("gone")
            },
            stillOwner = { r.owner },
        )
        assertNull(leaving.onEnded(s, "a"))
        assertEquals(listOf("bad"), r.started)
        assertEquals(listOf("bad", "c"), r.queue)
    }

    @Test fun aStartThatIsCancelled_isNoFailedStart() = runTest {
        val r = Throwing(mutableListOf("bad", "c"), failure = kotlinx.coroutines.CancellationException("the service is ending"))
        val thrown = runCatching { r.advancer.onEnded(s, "a") }.exceptionOrNull()
        assertTrue(thrown is kotlinx.coroutines.CancellationException)
        assertEquals(listOf("bad"), r.started)
        assertEquals("nothing was decided", listOf("bad", "c"), r.queue)
    }

    @Test fun sleepTimerAtEndOfChapter_startsNothing() = runTest {
        val r = Run(mutableListOf("a", "b"), emptyMap(), sleep = true)
        assertNull(r.advancer.onEnded(s, "a"))
        assertEquals(listOf("b"), r.queue)
    }
}
