package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY displayOrder")
    fun observeAll(serverId: String): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY displayOrder")
    suspend fun getAll(serverId: String): List<LibraryEntity>

    @Query("UPDATE libraries SET kindOverride = :kind WHERE serverId = :serverId AND id = :id")
    suspend fun setKindOverride(serverId: String, id: String, kind: LibraryKind?)
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteForItem(serverId: String, itemId: String)

    @Query("SELECT * FROM chapters WHERE serverId = :serverId AND itemId = :itemId ORDER BY `index`")
    suspend fun forItem(serverId: String, itemId: String): List<ChapterEntity>
}

@Dao
interface AudioFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<AudioFileEntity>)

    @Query("DELETE FROM audio_files WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun deleteForItem(serverId: String, itemId: String)

    @Query("SELECT * FROM audio_files WHERE serverId = :serverId AND itemId = :itemId ORDER BY `index`")
    suspend fun forItem(serverId: String, itemId: String): List<AudioFileEntity>

    @Query(
        "UPDATE audio_files SET localPath = :localPath, completedBytes = :completedBytes " +
            "WHERE serverId = :serverId AND itemId = :itemId AND `index` = :index",
    )
    suspend fun setLocal(serverId: String, itemId: String, index: Int, localPath: String?, completedBytes: Long)
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(progress: List<ProgressEntity>)

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun get(serverId: String, itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND dirty = 1")
    suspend fun dirty(serverId: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE serverId = :serverId")
    suspend fun all(serverId: String): List<ProgressEntity>
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: LocalSessionEntity)

    @Query("SELECT * FROM local_sessions WHERE serverId = :serverId AND synced = 0 ORDER BY startedAt")
    suspend fun unsynced(serverId: String): List<LocalSessionEntity>

    @Query("UPDATE local_sessions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun get(serverId: String, itemId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND state IN (:states)")
    suspend fun inStates(serverId: String, states: List<DownloadState>): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)
}
