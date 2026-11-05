package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.db.entities.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(episodes: List<EpisodeEntity>)

    /** Adds episodes not stored yet (e.g. from a playlist); existing rows are left alone. Returns -1 per skipped row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(episodes: List<EpisodeEntity>): List<Long>

    /** Removes episodes of one item that have neither a download nor progress (keyed `itemId~id`). */
    @Query(
        """DELETE FROM episodes WHERE serverId = :serverId AND itemId = :itemId
           AND NOT EXISTS (SELECT 1 FROM downloads d WHERE d.serverId = episodes.serverId AND d.itemId = episodes.itemId || '~' || episodes.id)
           AND NOT EXISTS (SELECT 1 FROM progress p WHERE p.serverId = episodes.serverId AND p.itemId = episodes.itemId || '~' || episodes.id)""",
    )
    protected abstract suspend fun deleteUnused(serverId: String, itemId: String)

    @Query("SELECT id FROM episodes WHERE serverId = :serverId AND itemId = :itemId AND recent = 1")
    protected abstract suspend fun recentIds(serverId: String, itemId: String): List<String>

    /** Drops audio files and chapters stored under the keys of episodes that no longer exist. */
    @Query(
        """DELETE FROM audio_files WHERE serverId = :serverId AND substr(itemId, 1, length(:itemId) + 1) = :itemId || '~'
           AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.serverId = audio_files.serverId AND e.itemId || '~' || e.id = audio_files.itemId)""",
    )
    protected abstract suspend fun deleteOrphanFiles(serverId: String, itemId: String)

    @Query(
        """DELETE FROM chapters WHERE serverId = :serverId AND substr(itemId, 1, length(:itemId) + 1) = :itemId || '~'
           AND NOT EXISTS (SELECT 1 FROM episodes e WHERE e.serverId = chapters.serverId AND e.itemId || '~' || e.id = chapters.itemId)""",
    )
    protected abstract suspend fun deleteOrphanChapters(serverId: String, itemId: String)

    /**
     * Replaces the item's episodes with [episodes]; rows with a download or progress survive even if absent from the list.
     * The `recent` flag of an existing row is kept (it is owned by the recent-episodes sync).
     */
    @Transaction
    open suspend fun replaceForItem(serverId: String, itemId: String, episodes: List<EpisodeEntity>) {
        val recent = recentIds(serverId, itemId).toSet()
        deleteUnused(serverId, itemId)
        upsert(episodes.map { it.copy(recent = it.recent || it.id in recent) })
        deleteOrphanFiles(serverId, itemId)
        deleteOrphanChapters(serverId, itemId)
    }

    @Query(
        """UPDATE episodes SET recent = 0 WHERE serverId = :serverId AND recent = 1
           AND itemId IN (SELECT id FROM items WHERE serverId = :serverId AND libraryId = :libraryId)""",
    )
    protected abstract suspend fun clearRecent(serverId: String, libraryId: String)

    /** Makes [episodes] (flagged `recent`) the library's recent set: resets the library's flags, then upserts them. */
    @Transaction
    open suspend fun replaceRecent(serverId: String, libraryId: String, episodes: List<EpisodeEntity>) {
        clearRecent(serverId, libraryId)
        upsert(episodes.map { it.copy(recent = true) })
    }

    /** Newest first. */
    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND itemId = :itemId ORDER BY publishedAt DESC, id")
    abstract fun observeForItem(serverId: String, itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND itemId = :itemId AND id = :id")
    abstract suspend fun get(serverId: String, itemId: String, id: String): EpisodeEntity?

    /** Joined with `items` so rows whose podcast is gone (and which no library sync can reset any more) never show. */
    @Query(
        """SELECT e.* FROM episodes e JOIN items i ON i.serverId = e.serverId AND i.id = e.itemId
           WHERE e.serverId = :serverId AND e.recent = 1 ORDER BY e.publishedAt DESC, e.id LIMIT :limit""",
    )
    abstract fun observeRecent(serverId: String, limit: Int): Flow<List<EpisodeEntity>>

    /** Episodes with unfinished, not hidden progress under their key, most recently heard first (the "continue" part of new episodes). */
    @Query(
        """SELECT e.* FROM episodes e JOIN progress p ON p.serverId = e.serverId AND p.itemId = e.itemId || '~' || e.id
           JOIN items i ON i.serverId = e.serverId AND i.id = e.itemId
           WHERE e.serverId = :serverId AND p.isFinished = 0 AND p.currentTimeSec > 0 AND p.hideFromContinue = 0
           ORDER BY p.lastUpdate DESC LIMIT :limit""",
    )
    abstract fun observeStarted(serverId: String, limit: Int): Flow<List<EpisodeEntity>>

    /** All `recent` episodes (newest first), for the auto-download after a sync. */
    @Query(
        """SELECT e.* FROM episodes e JOIN items i ON i.serverId = e.serverId AND i.id = e.itemId
           WHERE e.serverId = :serverId AND e.recent = 1 ORDER BY e.publishedAt DESC, e.id""",
    )
    abstract suspend fun recent(serverId: String): List<EpisodeEntity>
}
