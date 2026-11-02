package io.wolftown.lauschig.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.db.entities.BookmarkPending
import io.wolftown.lauschig.db.entities.CollectionEntity
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.db.entities.HistorySessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Phase3bDaoTest {
    private lateinit var db: LauschigDatabase
    private val s = "srv"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun bookmark_createThenDeleteBeforePush_leavesNothing() = runTest {
        val dao = db.bookmarkDao()
        dao.save(s, "li_1", 60.0, "n", now = 1)
        dao.delete(s, "li_1", 60.0)
        assertTrue(dao.all(s).isEmpty())
    }

    @Test fun bookmark_pushedThenDeleted_isHiddenTombstone_untilConfirmed() = runTest {
        val dao = db.bookmarkDao()
        dao.save(s, "li_1", 60.0, null, now = 1)
        val row = dao.get(s, "li_1", 60.0)!!
        dao.markPushed(s, "li_1", 60.0, row.rev)
        dao.delete(s, "li_1", 60.0)
        assertTrue(dao.observeForKey(s, "li_1").first().isEmpty())
        val tomb = dao.pending(s).single()
        assertEquals(BookmarkPending.DELETE, tomb.pending)
        dao.deletePushed(s, "li_1", 60.0, tomb.rev)
        assertTrue(dao.all(s).isEmpty())
    }

    @Test fun bookmark_editDuringPush_staysPending() = runTest {
        val dao = db.bookmarkDao()
        dao.save(s, "li_1", 60.0, "a", now = 1)
        val sent = dao.get(s, "li_1", 60.0)!!
        dao.save(s, "li_1", 60.0, "b", now = 2) // while the request was in flight
        dao.markPushed(s, "li_1", 60.0, sent.rev)
        val row = dao.get(s, "li_1", 60.0)!!
        assertEquals(BookmarkPending.UPSERT, row.pending)
        assertEquals("b", row.note)
    }

    @Test fun bookmark_merge_appliesPlan() = runTest {
        val dao = db.bookmarkDao()
        dao.save(s, "li_1", 10.0, "pending", now = 1)
        dao.mergeFromServer(s, listOf(BookmarkDto("li_1", 10.0, "server", 1), BookmarkDto("li_2", 5.0, "x", 3)))
        assertEquals("pending", dao.get(s, "li_1", 10.0)!!.note)
        val pulled = dao.get(s, "li_2", 5.0)!!
        assertEquals("x", pulled.note)
        assertEquals(BookmarkPending.NONE, pulled.pending)
        dao.mergeFromServer(s, emptyList())
        assertNull(dao.get(s, "li_2", 5.0))
        assertEquals(1, dao.all(s).size)
    }

    @Test fun queue_addMoveRemoveReplace() = runTest {
        val q = db.queueDao()
        q.add(s, "a", 1); q.add(s, "b", 2); q.add(s, "c", 3); q.add(s, "a", 4)
        assertEquals(listOf("a", "b", "c"), q.entries(s).map { it.itemKey })
        q.move(s, "c", -1)
        assertEquals(listOf("a", "c", "b"), q.entries(s).map { it.itemKey })
        q.move(s, "a", -5)
        assertEquals(listOf("a", "c", "b"), q.entries(s).map { it.itemKey })
        q.remove(s, "c")
        assertEquals(listOf(0, 1), q.entries(s).map { it.position })
        q.replace(s, listOf("x", "y", "x"), 9)
        assertEquals(listOf("x", "y"), q.observe(s).first().map { it.itemKey })
    }

    @Test fun collections_replacePerLibrary_playlistsSeparately() = runTest {
        val c = db.collectionDao()
        fun col(id: String, lib: String, kind: CollectionKind = CollectionKind.COLLECTION) = CollectionEntity(s, id, kind, lib, "N$id")
        c.replaceCollections(s, "lib1", listOf(col("c1", "lib1")), mapOf("c1" to listOf("b1", "b2")))
        c.replaceCollections(s, "lib2", listOf(col("c2", "lib2")), mapOf("c2" to listOf("b3")))
        c.replacePlaylists(s, listOf(col("p1", "lib1", CollectionKind.PLAYLIST)), mapOf("p1" to listOf("pod~e1")))
        c.replaceCollections(s, "lib1", emptyList(), emptyMap())
        val cols = c.observeByKind(s, CollectionKind.COLLECTION).first()
        assertEquals(listOf("c2"), cols.map { it.id })
        assertEquals(1, cols.single().count)
        assertEquals(listOf("pod~e1"), c.entries(s, "p1").map { it.itemKey })
        assertTrue(c.entries(s, "c1").isEmpty())
    }

    @Test fun history_replaceForKey() = runTest {
        val h = db.historyDao()
        fun row(id: String, key: String) = HistorySessionEntity(s, id, key, 1, 2, 0.0, 5.0, 5.0)
        h.replaceForKey(s, "a", listOf(row("1", "a"), row("2", "a")))
        h.replaceForKey(s, "b", listOf(row("3", "b")))
        h.replaceForKey(s, "a", listOf(row("4", "a")))
        assertEquals(listOf("4"), h.observeForKey(s, "a").first().map { it.id })
        assertEquals(listOf("3"), h.observeForKey(s, "b").first().map { it.id })
    }
}
