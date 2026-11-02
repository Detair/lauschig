package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.db.entities.HistorySessionEntity
import kotlinx.coroutines.flow.Flow

/** Cache of the server's listening sessions per title (for the history while offline). */
@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM history_sessions WHERE serverId = :serverId AND itemKey = :itemKey")
    abstract fun observeForKey(serverId: String, itemKey: String): Flow<List<HistorySessionEntity>>

    @Query("DELETE FROM history_sessions WHERE serverId = :serverId AND itemKey = :itemKey")
    protected abstract suspend fun deleteForKey(serverId: String, itemKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(rows: List<HistorySessionEntity>)

    @Transaction
    open suspend fun replaceForKey(serverId: String, itemKey: String, rows: List<HistorySessionEntity>) {
        deleteForKey(serverId, itemKey)
        insertAll(rows)
    }
}
