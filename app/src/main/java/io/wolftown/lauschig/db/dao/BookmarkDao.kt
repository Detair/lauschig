package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.bookmarks.BookmarkRules
import io.wolftown.lauschig.db.entities.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/** Bookmarks plus their outbox; every local change goes through [save] / [delete] (see [BookmarkRules]). */
@Dao
abstract class BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(row: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE serverId = :serverId AND itemKey = :itemKey AND timeSec = :timeSec")
    protected abstract suspend fun remove(serverId: String, itemKey: String, timeSec: Double)

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND itemKey = :itemKey AND timeSec = :timeSec")
    abstract suspend fun get(serverId: String, itemKey: String, timeSec: Double): BookmarkEntity?

    /** Visible bookmarks (tombstones hidden), by position. */
    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND itemKey = :itemKey AND pending != 'DELETE' ORDER BY timeSec")
    abstract fun observeForKey(serverId: String, itemKey: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId AND pending != 'NONE'")
    abstract suspend fun pending(serverId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE serverId = :serverId")
    abstract suspend fun all(serverId: String): List<BookmarkEntity>

    @Transaction
    open suspend fun save(serverId: String, itemKey: String, timeSec: Double, note: String?, now: Long) {
        put(BookmarkRules.save(get(serverId, itemKey, timeSec), serverId, itemKey, timeSec, note, now))
    }

    @Transaction
    open suspend fun delete(serverId: String, itemKey: String, timeSec: Double) {
        val row = get(serverId, itemKey, timeSec) ?: return
        val next = BookmarkRules.delete(row)
        if (next == null) remove(serverId, itemKey, timeSec) else put(next)
    }

    /** The server accepted the create/edit; ignored if the row changed meanwhile ([rev]). */
    @Query(
        "UPDATE bookmarks SET pending = 'NONE', onServer = 1 " +
            "WHERE serverId = :serverId AND itemKey = :itemKey AND timeSec = :timeSec AND rev = :rev AND pending = 'UPSERT'",
    )
    abstract suspend fun markPushed(serverId: String, itemKey: String, timeSec: Double, rev: Long)

    /** The server removed it; the tombstone goes unless it was revived meanwhile. */
    @Query("DELETE FROM bookmarks WHERE serverId = :serverId AND itemKey = :itemKey AND timeSec = :timeSec AND rev = :rev AND pending = 'DELETE'")
    abstract suspend fun deletePushed(serverId: String, itemKey: String, timeSec: Double, rev: Long)

    /** Applies the server list (see [BookmarkRules.merge]) against the current local state, atomically. */
    @Transaction
    open suspend fun mergeFromServer(serverId: String, server: List<BookmarkDto>) {
        val plan = BookmarkRules.merge(serverId, all(serverId), server)
        plan.deletes.forEach { remove(it.serverId, it.itemKey, it.timeSec) }
        plan.upserts.forEach { put(it) }
    }
}
