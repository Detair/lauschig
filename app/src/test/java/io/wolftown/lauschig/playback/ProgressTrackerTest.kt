package io.wolftown.lauschig.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class ProgressTrackerTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private var pos = 0.0
    private var n = 0

    @Before fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
    }

    @After fun tearDown() = db.close()

    private fun TestScope.tracker() = ProgressTracker(
        db.progressDao(), db.sessionDao(), clock = { currentTime }, scope = backgroundScope, newId = { "sess${n++}" },
    )

    private suspend fun ProgressTracker.play(item: String = "it", duration: Double = 1000.0) =
        onPlaying(s, item, duration) { pos }

    @Test fun tracker_savesEvery10s_andOnPause() = runTest {
        val t = tracker()
        pos = 0.0
        t.play()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(null, db.progressDao().get(s, "it")?.takeIf { it.lastUpdate > 0 }?.lastUpdate)
        pos = 10.0
        advanceTimeBy(1); runCurrent()
        assertEquals(10.0, db.progressDao().get(s, "it")!!.currentTimeSec, 0.0)
        assertEquals(10_000L, db.progressDao().get(s, "it")!!.lastUpdate)
        assertTrue(db.progressDao().get(s, "it")!!.dirty)
        pos = 20.0
        advanceTimeBy(10_000); runCurrent()
        assertEquals(20.0, db.progressDao().get(s, "it")!!.currentTimeSec, 0.0)
        pos = 27.0
        advanceTimeBy(3_000); runCurrent()
        t.onPaused()
        assertEquals(27.0, db.progressDao().get(s, "it")!!.currentTimeSec, 0.0)
        // no more ticks after pause
        pos = 99.0
        advanceTimeBy(60_000); runCurrent()
        assertEquals(27.0, db.progressDao().get(s, "it")!!.currentTimeSec, 0.0)
    }

    @Test fun tracker_accumulatesListeningTime_inOneSession() = runTest {
        val t = tracker()
        t.play()
        advanceTimeBy(30_000); pos = 30.0
        t.onPaused()
        advanceTimeBy(120_000)
        t.play()
        advanceTimeBy(30_000); pos = 60.0
        t.onPaused()
        val sessions = db.sessionDao().unsynced(s)
        assertEquals(1, sessions.size)
        assertEquals(60.0, sessions[0].timeListeningSec, 0.001)
        assertEquals(0.0, sessions[0].startTimeSec, 0.0)
        assertEquals(60.0, sessions[0].currentTimeSec, 0.0)
    }

    @Test fun tracker_listeningTimeIsRealTime_notMediaTime() = runTest {
        val t = tracker()
        t.play()
        advanceTimeBy(60_000); pos = 120.0 // 2x speed: 120 s of media in 60 s
        t.onPaused()
        assertEquals(60.0, db.sessionDao().unsynced(s).single().timeListeningSec, 0.001)
    }

    @Test fun tracker_newSessionAfterLongPause() = runTest {
        val t = tracker()
        t.play()
        advanceTimeBy(30_000); pos = 30.0
        t.onPaused()
        advanceTimeBy(11 * 60_000L)
        t.play()
        advanceTimeBy(30_000); pos = 60.0
        t.onPaused()
        val sessions = db.sessionDao().unsynced(s)
        assertEquals(2, sessions.size)
        assertEquals(30.0, sessions[0].timeListeningSec, 0.001)
        assertEquals(30.0, sessions[1].timeListeningSec, 0.001)
        assertEquals(30.0, sessions[1].startTimeSec, 0.0)
    }

    @Test fun tracker_pauseOfExactlyTenMinutes_keepsSession() = runTest {
        val t = tracker()
        t.play(); advanceTimeBy(1_000); t.onPaused()
        advanceTimeBy(10 * 60_000L)
        t.play(); advanceTimeBy(1_000); t.onPaused()
        assertEquals(1, db.sessionDao().unsynced(s).size)
    }

    @Test fun tracker_newSessionOnTitleChange() = runTest {
        val t = tracker()
        t.play("a"); advanceTimeBy(5_000); pos = 5.0
        t.play("b") // switch while playing
        advanceTimeBy(5_000); t.onPaused()
        val sessions = db.sessionDao().unsynced(s)
        assertEquals(2, sessions.size)
        assertEquals(setOf("a", "b"), sessions.map { it.itemId }.toSet())
        assertEquals(5.0, db.progressDao().get(s, "a")!!.currentTimeSec, 0.0)
        assertNotEquals(sessions[0].id, sessions[1].id)
    }

    @Test fun finishedAt99_5Percent() = runTest {
        val t = tracker()
        pos = 994.0
        t.play(duration = 1000.0); advanceTimeBy(1_000); t.onPaused()
        assertFalse(db.progressDao().get(s, "it")!!.isFinished)
        pos = 995.0
        t.play(duration = 1000.0); advanceTimeBy(1_000); t.onPaused()
        assertTrue(db.progressDao().get(s, "it")!!.isFinished)
    }

    @Test fun finishedAtEndOfLastFile() = runTest {
        val t = tracker()
        pos = 400.0
        t.play(duration = 1000.0); advanceTimeBy(1_000)
        t.onEnded()
        val p = db.progressDao().get(s, "it")!!
        assertTrue(p.isFinished)
        assertEquals(1000.0, p.currentTimeSec, 0.0)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(p, db.progressDao().get(s, "it"))
    }
}
