package io.wolftown.lauschig.bookmarks

import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.db.entities.BookmarkPending.DELETE
import io.wolftown.lauschig.db.entities.BookmarkPending.NONE
import io.wolftown.lauschig.db.entities.BookmarkPending.UPSERT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRulesTest {
    private val s = "srv"
    private fun row(key: String, t: Double, note: String? = null, pending: io.wolftown.lauschig.db.entities.BookmarkPending = NONE, onServer: Boolean = true, rev: Long = 0) =
        BookmarkEntity(s, key, t, note, 1, pending, onServer, rev)

    @Test fun defaultTitle_isTheClock_andRoundTripsAsNoNote() {
        assertEquals("02:03", BookmarkRules.defaultTitle(123.9))
        assertEquals("1:02:03", BookmarkRules.defaultTitle(3723.0))
        assertEquals("02:03", BookmarkRules.serverTitle(123.0, null))
        assertEquals("02:03", BookmarkRules.serverTitle(123.0, "  "))
        assertEquals("Wichtig", BookmarkRules.serverTitle(123.0, " Wichtig "))
        assertNull(BookmarkRules.noteFromTitle(123.0, "02:03"))
        assertEquals("Wichtig", BookmarkRules.noteFromTitle(123.0, "Wichtig"))
    }

    @Test fun syncable_onlyBooks() {
        assertTrue(BookmarkRules.isSyncable("li_1"))
        assertFalse(BookmarkRules.isSyncable("li_pod~ep_1"))
    }

    @Test fun save_new_book_isPendingUpsert_episode_isLocalOnly() {
        val book = BookmarkRules.save(null, s, "li_1", 60.0, "n", now = 5)
        assertEquals(UPSERT, book.pending)
        assertFalse(book.onServer)
        assertEquals(5L, book.createdAt)
        val ep = BookmarkRules.save(null, s, "li_p~e", 60.0, null, now = 5)
        assertEquals(NONE, ep.pending)
    }

    @Test fun save_existing_keepsCreatedAndOnServer_bumpsRev_andRevivesTombstone() {
        val edited = BookmarkRules.save(row("li_1", 60.0, "a", rev = 3), s, "li_1", 60.0, "b", now = 9)
        assertEquals("b", edited.note)
        assertEquals(UPSERT, edited.pending)
        assertTrue(edited.onServer)
        assertEquals(1L, edited.createdAt)
        assertEquals(4L, edited.rev)
        val revived = BookmarkRules.save(row("li_1", 60.0, pending = DELETE), s, "li_1", 60.0, "c", now = 9)
        assertEquals(UPSERT, revived.pending)
    }

    @Test fun delete_neverPushed_removesRow_pushed_becomesTombstone() {
        assertNull(BookmarkRules.delete(row("li_1", 60.0, pending = UPSERT, onServer = false)))
        assertNull(BookmarkRules.delete(row("li_p~e", 60.0, onServer = false)))
        val tomb = BookmarkRules.delete(row("li_1", 60.0, rev = 1))!!
        assertEquals(DELETE, tomb.pending)
        assertEquals(2L, tomb.rev)
    }

    @Test fun merge_insertsNew_takesServerTitleForSynced_deletesVanishedSynced() {
        val local = listOf(
            row("li_1", 10.0, "old"),          // synced, server has new title
            row("li_1", 20.0, "gone"),         // synced, removed elsewhere
            row("li_2", 30.0, onServer = false), // synced but never on server (cannot happen for books, but must survive)
        )
        val server = listOf(BookmarkDto("li_1", 10.0, "new", 1), BookmarkDto("li_3", 40.0, "00:40", 7))

        val plan = BookmarkRules.merge(s, local, server)

        assertEquals(listOf(Triple("li_1", 20.0, "")), plan.deletes.map { Triple(it.itemKey, it.timeSec, "") })
        val byKey = plan.upserts.associateBy { it.itemKey to it.timeSec }
        assertEquals("new", byKey.getValue("li_1" to 10.0).note)
        val inserted = byKey.getValue("li_3" to 40.0)
        assertNull(inserted.note)
        assertEquals(NONE, inserted.pending)
        assertTrue(inserted.onServer)
        assertEquals(7L, inserted.createdAt)
        assertEquals(2, plan.upserts.size)
    }

    @Test fun merge_pendingRowsWin() {
        val local = listOf(
            row("li_1", 10.0, "mine", pending = UPSERT),       // local edit beats the server title
            row("li_1", 20.0, pending = DELETE),               // local delete beats the server row
            row("li_1", 30.0, "edit", pending = UPSERT),       // vanished on server while edited: kept; the push PATCHes, gets 404 and creates it
        )
        val server = listOf(BookmarkDto("li_1", 10.0, "theirs", 1), BookmarkDto("li_1", 20.0, "x", 1))

        val plan = BookmarkRules.merge(s, local, server)

        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test fun merge_ignoresEpisodeRows() {
        val plan = BookmarkRules.merge(s, listOf(row("li_p~e", 10.0, onServer = true)), emptyList())
        assertTrue(plan.deletes.isEmpty())
    }
}
