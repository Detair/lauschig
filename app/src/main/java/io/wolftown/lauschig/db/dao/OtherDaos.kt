package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
abstract class LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(libraries: List<LibraryEntity>)

    @Query(
        "UPDATE libraries SET name = :name, mediaType = :mediaType, displayOrder = :displayOrder, kind = :kind " +
            "WHERE serverId = :serverId AND id = :id",
    )
    protected abstract suspend fun updateServerColumns(
        serverId: String, id: String, name: String, mediaType: String, displayOrder: Int, kind: LibraryKind,
    )

    /** Inserts new libraries; existing ones only get server-owned columns updated (`kindOverride` is kept). */
    @Transaction
    open suspend fun upsertFromServer(libraries: List<LibraryEntity>) {
        insertIgnore(libraries)
        for (l in libraries) updateServerColumns(l.serverId, l.id, l.name, l.mediaType, l.displayOrder, l.kind)
    }

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY displayOrder")
    abstract fun observeAll(serverId: String): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY displayOrder")
    abstract suspend fun getAll(serverId: String): List<LibraryEntity>

    @Query("UPDATE libraries SET kindOverride = :kind WHERE serverId = :serverId AND id = :id")
    abstract suspend fun setKindOverride(serverId: String, id: String, kind: LibraryKind?)
}

@Dao
abstract class ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE serverId = :serverId AND itemId = :itemId")
    protected abstract suspend fun deleteForItem(serverId: String, itemId: String)

    /** Atomic delete + insert so observers never see an empty chapter list mid-way. */
    @Transaction
    open suspend fun replaceFromServer(serverId: String, itemId: String, chapters: List<ChapterEntity>) {
        deleteForItem(serverId, itemId)
        insertAll(chapters)
    }

    @Query("SELECT * FROM chapters WHERE serverId = :serverId AND itemId = :itemId ORDER BY `index`")
    abstract suspend fun forItem(serverId: String, itemId: String): List<ChapterEntity>
}

@Dao
abstract class AudioFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(files: List<AudioFileEntity>)

    @Query("DELETE FROM audio_files WHERE serverId = :serverId AND itemId = :itemId")
    protected abstract suspend fun deleteForItem(serverId: String, itemId: String)

    /**
     * Replaces the file list with the server's, carrying over `localPath`/`completedBytes` from the existing
     * row with the same `ino` (fallback: same index, only if either side's ino is blank). Vanished files are
     * dropped. [setLocal] stays the only other writer of the local columns.
     */
    @Transaction
    open suspend fun replaceFromServer(serverId: String, itemId: String, files: List<AudioFileEntity>) {
        val old = forItem(serverId, itemId)
        val byIno = old.filter { it.ino.isNotBlank() }.associateBy { it.ino }
        val byIndex = old.associateBy { it.index }
        val merged = files.map { f ->
            val prev = byIno[f.ino] ?: byIndex[f.index]?.takeIf { it.ino.isBlank() || f.ino.isBlank() }
            if (prev == null) f.copy(localPath = null, completedBytes = 0)
            else f.copy(localPath = prev.localPath, completedBytes = prev.completedBytes)
        }
        deleteForItem(serverId, itemId)
        insertAll(merged)
    }

    @Query("SELECT * FROM audio_files WHERE serverId = :serverId AND itemId = :itemId ORDER BY `index`")
    abstract suspend fun forItem(serverId: String, itemId: String): List<AudioFileEntity>

    @Query(
        "UPDATE audio_files SET localPath = :localPath, completedBytes = :completedBytes " +
            "WHERE serverId = :serverId AND itemId = :itemId AND `index` = :index",
    )
    abstract suspend fun setLocal(serverId: String, itemId: String, index: Int, localPath: String?, completedBytes: Long)
}

/** A server row that replaced a local one; input for PositionJump detection. */
data class ProgressChange(val itemId: String, val oldCurrentTimeSec: Double, val newCurrentTimeSec: Double)

@Dao
abstract class ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun put(progress: ProgressEntity)

    /** Newest-wins merge: a server row is applied only if its lastUpdate is strictly newer than the local one. */
    @Transaction
    open suspend fun mergeFromServer(rows: List<ProgressEntity>): List<ProgressChange> {
        val changes = mutableListOf<ProgressChange>()
        for (row in rows) {
            val local = get(row.serverId, row.itemId)
            if (local != null && row.lastUpdate <= local.lastUpdate) continue
            put(row.copy(dirty = false))
            if (local != null) changes += ProgressChange(row.itemId, local.currentTimeSec, row.currentTimeSec)
        }
        return changes
    }

    /** Local (player) write; always marks the row dirty. */
    open suspend fun saveLocal(
        serverId: String, itemId: String, currentTimeSec: Double, durationSec: Double, isFinished: Boolean, lastUpdate: Long,
    ) = put(ProgressEntity(serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty = true))

    /** Clears dirty only if no newer local write happened since [lastUpdate] was read. */
    @Query("UPDATE progress SET dirty = 0 WHERE serverId = :serverId AND itemId = :itemId AND lastUpdate = :lastUpdate")
    abstract suspend fun markSynced(serverId: String, itemId: String, lastUpdate: Long)

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND itemId = :itemId")
    abstract suspend fun get(serverId: String, itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND dirty = 1")
    abstract suspend fun dirty(serverId: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE serverId = :serverId")
    abstract suspend fun all(serverId: String): List<ProgressEntity>
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
