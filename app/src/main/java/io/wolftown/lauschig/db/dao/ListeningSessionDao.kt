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
}
