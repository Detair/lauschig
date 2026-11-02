package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.wolftown.lauschig.db.entities.ExternalSeriesEntity

@Dao
interface ExternalSeriesDao {
    @Query("SELECT * FROM external_series WHERE serverId = :serverId AND libraryId = :libraryId AND seriesName = :seriesName")
    suspend fun get(serverId: String, libraryId: String, seriesName: String): ExternalSeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExternalSeriesEntity)
}
