package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import kotlinx.coroutines.flow.Flow

/** The achievement tiers each account was told about (see `AchievementNotices`). */
@Dao
interface AchievementSeenDao {
    @Query("SELECT * FROM achievement_seen WHERE serverId = :serverId")
    fun observeAll(serverId: String): Flow<List<AchievementSeenEntity>>

    /** In one transaction; a tier already seen keeps its first time. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<AchievementSeenEntity>)
}
