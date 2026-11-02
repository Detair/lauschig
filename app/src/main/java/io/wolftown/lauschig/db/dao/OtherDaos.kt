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

    /** Number of completely downloaded files; changes when a download finishes or is deleted. */
    @Query("SELECT COUNT(*) FROM audio_files WHERE serverId = :serverId AND itemId = :itemId AND localPath IS NOT NULL")
    abstract fun observeLocalCount(serverId: String, itemId: String): Flow<Int>

    @Query(
        "UPDATE audio_files SET localPath = :localPath, completedBytes = :completedBytes " +
            "WHERE serverId = :serverId AND itemId = :itemId AND `index` = :index",
    )
    abstract suspend fun setLocal(serverId: String, itemId: String, index: Int, localPath: String?, completedBytes: Long)

    /** Records partial progress of an in-flight download; never touches `localPath` (only [setLocal] marks completion). */
    @Query(
        "UPDATE audio_files SET completedBytes = :completedBytes " +
            "WHERE serverId = :serverId AND itemId = :itemId AND `index` = :index AND localPath IS NULL " +
            "AND EXISTS (SELECT 1 FROM downloads d WHERE d.serverId = :serverId AND d.itemId = :itemId)",
    )
    abstract suspend fun setProgress(serverId: String, itemId: String, index: Int, completedBytes: Long)

    /** Clears all local columns of an item (download removed). */
    @Query("UPDATE audio_files SET localPath = NULL, completedBytes = 0 WHERE serverId = :serverId AND itemId = :itemId")
    abstract suspend fun clearLocal(serverId: String, itemId: String)

    @Query(
        "SELECT COALESCE(SUM(completedBytes), 0) AS done, COALESCE(SUM(sizeBytes), 0) AS total " +
            "FROM audio_files WHERE serverId = :serverId AND itemId = :itemId",
    )
    abstract fun observeBytes(serverId: String, itemId: String): Flow<BytesProgress>

    /** Bytes on disk per downloaded item (for the cleanup page). */
    @Query(
        "SELECT itemId, COALESCE(SUM(completedBytes), 0) AS bytes FROM audio_files " +
            "WHERE serverId = :serverId AND localPath IS NOT NULL GROUP BY itemId",
    )
    abstract fun observeLocalBytes(serverId: String): Flow<List<ItemBytes>>
}

data class BytesProgress(val done: Long, val total: Long)

data class ItemBytes(val itemId: String, val bytes: Long)

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

    /** Explicit user action (mark finished, start over, hide): written like a player save, so it syncs as newest. */
    open suspend fun saveUserAction(row: ProgressEntity) = put(row.copy(dirty = true))

    /** "Fetch from server": takes the server row as-is, even if the local one is newer. */
    open suspend fun replaceFromServer(row: ProgressEntity) = put(row.copy(dirty = false))

    /** Clears dirty only if no newer local write happened since [lastUpdate] was read. */
    @Query("UPDATE progress SET dirty = 0 WHERE serverId = :serverId AND itemId = :itemId AND lastUpdate = :lastUpdate")
    abstract suspend fun markSynced(serverId: String, itemId: String, lastUpdate: Long)

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND itemId = :itemId")
    abstract suspend fun get(serverId: String, itemId: String): ProgressEntity?

    /** True if [itemId] or any of its episodes (`itemId~episodeId`) has a progress row. ABS ids never contain `~` or LIKE wildcards, so a prefix compare is exact. */
    @Query("SELECT EXISTS(SELECT 1 FROM progress WHERE serverId = :serverId AND (itemId = :itemId OR substr(itemId, 1, length(:itemId) + 1) = :itemId || '~'))")
    abstract suspend fun hasAnyForItem(serverId: String, itemId: String): Boolean

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND dirty = 1")
    abstract suspend fun dirty(serverId: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE serverId = :serverId")
    abstract suspend fun all(serverId: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE serverId = :serverId")
    abstract fun observeAll(serverId: String): kotlinx.coroutines.flow.Flow<List<ProgressEntity>>
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

    /** True if [itemId] or any of its episodes (`itemId~episodeId`) has a downloads row. ABS ids never contain `~` or LIKE wildcards, so a prefix compare is exact. */
    @Query("SELECT EXISTS(SELECT 1 FROM downloads WHERE serverId = :serverId AND (itemId = :itemId OR substr(itemId, 1, length(:itemId) + 1) = :itemId || '~'))")
    suspend fun hasAnyForItem(serverId: String, itemId: String): Boolean

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND state IN (:states)")
    suspend fun inStates(serverId: String, states: List<DownloadState>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    fun observe(serverId: String, itemId: String): Flow<DownloadEntity?>

    /** Items whose download is queued or running (for the "downloading" cover badge). */
    @Query("SELECT itemId FROM downloads WHERE serverId = :serverId AND state IN ('QUEUED', 'RUNNING')")
    fun observeActiveIds(serverId: String): Flow<List<String>>

    /** Keys (books and episodes) whose download is complete. */
    @Query("SELECT itemId FROM downloads WHERE serverId = :serverId AND state = 'COMPLETE'")
    fun observeCompleteIds(serverId: String): Flow<List<String>>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun setState(serverId: String, itemId: String, state: DownloadState, error: String?)

    /** Conditional transition (e.g. RUNNING -> QUEUED when a worker is stopped) that never overrides PAUSED/removed rows. */
    @Query(
        "UPDATE downloads SET state = :to, error = :error " +
            "WHERE serverId = :serverId AND itemId = :itemId AND state = :from",
    )
    suspend fun setStateIf(serverId: String, itemId: String, from: DownloadState, to: DownloadState, error: String?)

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(serverId: String, itemId: String)
}
