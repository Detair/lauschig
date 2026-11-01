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

    /** Removes episodes of one item that have neither a download nor progress (keyed `itemId~id`). */
    @Query(
        """DELETE FROM episodes WHERE serverId = :serverId AND itemId = :itemId
           AND NOT EXISTS (SELECT 1 FROM downloads d WHERE d.serverId = episodes.serverId AND d.itemId = episodes.itemId || '~' || episodes.id)
           AND NOT EXISTS (SELECT 1 FROM progress p WHERE p.serverId = episodes.serverId AND p.itemId = episodes.itemId || '~' || episodes.id)""",
    )
    protected abstract suspend fun deleteUnused(serverId: String, itemId: String)

    /** Replaces the item's episodes with [episodes]; rows with a download or progress survive even if absent from the list. */
    @Transaction
    open suspend fun replaceForItem(serverId: String, itemId: String, episodes: List<EpisodeEntity>) {
        deleteUnused(serverId, itemId)
        upsert(episodes)
    }

    /** Newest first. */
    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND itemId = :itemId ORDER BY publishedAt DESC, id")
    abstract fun observeForItem(serverId: String, itemId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND itemId = :itemId AND id = :id")
    abstract suspend fun get(serverId: String, itemId: String, id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE serverId = :serverId AND recent = 1 ORDER BY publishedAt DESC, id LIMIT :limit")
    abstract fun observeRecent(serverId: String, limit: Int): Flow<List<EpisodeEntity>>
}
