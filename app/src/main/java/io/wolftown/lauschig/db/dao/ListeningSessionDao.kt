package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import kotlinx.coroutines.flow.Flow

/** The account's listening sessions as the server reported them (see `ListeningSessionSync`), for the statistics. */
@Dao
interface ListeningSessionDao {
    /** New sessions are added, known ones (same server id and id) take the server's newer values. */
    @Upsert
    suspend fun upsertAll(rows: List<ListeningSessionEntity>)

    @Query("SELECT * FROM listening_sessions WHERE serverId = :serverId")
    fun observeAll(serverId: String): Flow<List<ListeningSessionEntity>>

    @Query("SELECT COUNT(*) FROM listening_sessions WHERE serverId = :serverId")
    suspend fun count(serverId: String): Int

    /** The newest `updatedAt` in the cache; null when it is empty. */
    @Query("SELECT MAX(updatedAt) FROM listening_sessions WHERE serverId = :serverId")
    suspend fun newestUpdatedAt(serverId: String): Long?

    @Query("SELECT id FROM listening_sessions WHERE serverId = :serverId")
    suspend fun ids(serverId: String): List<String>

    /** At most [ListeningSessionDao.DELETE_CHUNK] ids per call (SQLite's variable limit on older Androids is 999). */
    @Query("DELETE FROM listening_sessions WHERE serverId = :serverId AND id IN (:ids)")
    suspend fun delete(serverId: String, ids: List<String>)

    companion object {
        const val DELETE_CHUNK = 500
    }
}
