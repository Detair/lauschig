package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EXPLAIN QUERY PLAN of the hot queries (the SQL is copied from ItemDao / ProgressDao / DownloadDao): the Phase 6
 * compound indexes must be what SQLite picks. Plans are printed so a regression shows what it chose instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QueryPlanTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()

    @After fun tearDown() = db.close()

    private fun plan(sql: String): String {
        val c = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql")
        val lines = buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        c.close()
        return lines.joinToString("\n").also { println("PLAN [$sql]\n$it\n") }
    }

    private val sid = "'aaaaaaaaaaaaaaaa'"

    @Test fun progress_unfinishedByRecency_usesCompoundIndex() {
        val p = plan("SELECT * FROM progress WHERE serverId = $sid AND isFinished = 0 ORDER BY lastUpdate DESC LIMIT 1")
        assertTrue(p, "index_progress_serverId_isFinished_lastUpdate" in p)
        assertTrue(p, "TEMP B-TREE" !in p)
    }

    @Test fun progress_finishedList_usesCompoundIndex() {
        val p = plan(
            """SELECT i.* FROM items i JOIN progress p ON p.serverId = i.serverId AND p.itemId = i.id
               WHERE i.serverId = $sid AND p.isFinished = 1 ORDER BY p.lastUpdate DESC""",
        )
        assertTrue(p, "index_progress_serverId_isFinished_lastUpdate" in p)
    }

    @Test fun downloads_byState_usesCompoundIndex() {
        val complete = plan("SELECT itemId FROM downloads WHERE serverId = $sid AND state = 'COMPLETE'")
        assertTrue(complete, "index_downloads_serverId_state" in complete)
        val active = plan("SELECT itemId FROM downloads WHERE serverId = $sid AND state IN ('QUEUED', 'RUNNING')")
        assertTrue(active, "index_downloads_serverId_state" in active)
        val inStates = plan("SELECT * FROM downloads WHERE serverId = $sid AND state IN ('QUEUED', 'PAUSED')")
        assertTrue(inStates, "index_downloads_serverId_state" in inStates)
    }

    @Test fun items_nextInSeries_usesSeriesIndex() {
        val p = plan(
            """SELECT * FROM items i WHERE serverId = $sid AND seriesName IS NOT NULL
                 AND lower(trim(seriesName)) = lower(trim('Reihe')) AND seriesSortKey > 1.0
                 AND NOT EXISTS (SELECT 1 FROM progress p WHERE p.serverId = i.serverId AND p.itemId = i.id AND p.isFinished = 1)
               ORDER BY seriesSortKey, title COLLATE NOCASE LIMIT 1""",
        )
        assertTrue(p, "index_items_serverId_seriesName_seriesSortKey" in p)
    }
}
