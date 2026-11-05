package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.db.entities.QueueEntity
import io.wolftown.lauschig.queue.QueueOrder
import kotlinx.coroutines.flow.Flow

/** The device-local "play next" queue; every change rewrites dense positions (see [QueueOrder]). */
@Dao
abstract class QueueDao {
    @Query("SELECT * FROM queue WHERE serverId = :serverId ORDER BY position")
    abstract fun observe(serverId: String): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queue WHERE serverId = :serverId ORDER BY position")
    abstract suspend fun entries(serverId: String): List<QueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(rows: List<QueueEntity>)

    @Query("DELETE FROM queue WHERE serverId = :serverId")
    abstract suspend fun clear(serverId: String)

    private suspend fun write(serverId: String, rows: List<QueueEntity>) {
        clear(serverId)
        insertAll(rows.mapIndexed { i, r -> r.copy(serverId = serverId, position = i) })
    }

    /** Appends [itemKey]; a title already queued keeps its place. */
    @Transaction
    open suspend fun add(serverId: String, itemKey: String, now: Long) = write(serverId, QueueOrder.add(entries(serverId), serverId, itemKey, now))

    @Transaction
    open suspend fun remove(serverId: String, itemKey: String) = write(serverId, QueueOrder.remove(entries(serverId), itemKey))

    /** Moves [itemKey] by [delta] places (negative = towards the front), clamped. */
    @Transaction
    open suspend fun move(serverId: String, itemKey: String, delta: Int) = write(serverId, QueueOrder.move(entries(serverId), itemKey, delta))

    /** Replaces the whole queue with [keys] (duplicates dropped). */
    @Transaction
    open suspend fun replace(serverId: String, keys: List<String>, now: Long) =
        write(serverId, QueueOrder.add(emptyList(), serverId, keys, now))
}
