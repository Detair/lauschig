package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

@Dao
abstract class ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(items: List<ItemEntity>)

    @Query("SELECT * FROM items WHERE serverId = :serverId AND id = :id")
    abstract suspend fun get(serverId: String, id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE serverId = :serverId AND id = :id")
    abstract fun observeItemRow(serverId: String, id: String): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE serverId = :serverId AND libraryId = :libraryId")
    abstract fun observeByLibrary(serverId: String, libraryId: String): Flow<List<ItemEntity>>

    @Query("SELECT id FROM items WHERE serverId = :serverId AND libraryId = :libraryId")
    abstract suspend fun idsInLibrary(serverId: String, libraryId: String): List<String>

    @Query("DELETE FROM items WHERE serverId = :serverId AND id IN (:ids)")
    abstract suspend fun deleteByIds(serverId: String, ids: List<String>)

    @Query("UPDATE items SET orphaned = :orphaned WHERE serverId = :serverId AND id IN (:ids)")
    abstract suspend fun setOrphaned(serverId: String, ids: List<String>, orphaned: Boolean)

    @Query(
        """SELECT i.* FROM items i JOIN progress p ON p.serverId = i.serverId AND p.itemId = i.id
           WHERE i.serverId = :serverId AND p.isFinished = 0 AND p.currentTimeSec > 0
           ORDER BY p.lastUpdate DESC""",
    )
    abstract fun observeInProgress(serverId: String): Flow<List<ItemEntity>>

    @Query(
        """SELECT i.* FROM items i JOIN progress p ON p.serverId = i.serverId AND p.itemId = i.id
           WHERE i.serverId = :serverId AND p.isFinished = 1
           ORDER BY p.lastUpdate DESC""",
    )
    abstract fun observeFinished(serverId: String): Flow<List<ItemEntity>>

    /** Downloaded = download COMPLETE and at least one audio file, all of them with a localPath. */
    @Query(
        """SELECT i.* FROM items i JOIN downloads d ON d.serverId = i.serverId AND d.itemId = i.id
           WHERE i.serverId = :serverId AND d.state = 'COMPLETE'
             AND EXISTS (SELECT 1 FROM audio_files a WHERE a.serverId = i.serverId AND a.itemId = i.id)
             AND NOT EXISTS (SELECT 1 FROM audio_files a WHERE a.serverId = i.serverId AND a.itemId = i.id
                             AND a.localPath IS NULL)
           ORDER BY i.title COLLATE NOCASE""",
    )
    abstract fun observeDownloaded(serverId: String): Flow<List<ItemEntity>>

    /** Filters by the library's effective kind (override, else derived kind). */
    @Query(
        """SELECT i.* FROM items i JOIN libraries l ON l.serverId = i.serverId AND l.id = i.libraryId
           WHERE i.serverId = :serverId AND COALESCE(l.kindOverride, l.kind) IN (:kinds)
           ORDER BY i.title COLLATE NOCASE""",
    )
    abstract fun observeByLibraryKinds(serverId: String, kinds: List<LibraryKind>): Flow<List<ItemEntity>>

    @Query("SELECT * FROM chapters WHERE serverId = :serverId AND itemId = :id ORDER BY `index`")
    abstract fun observeChapters(serverId: String, id: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM audio_files WHERE serverId = :serverId AND itemId = :id ORDER BY `index`")
    abstract fun observeAudioFiles(serverId: String, id: String): Flow<List<AudioFileEntity>>

    @Query("SELECT * FROM progress WHERE serverId = :serverId AND itemId = :id")
    abstract fun observeProgress(serverId: String, id: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND itemId = :id")
    abstract fun observeDownload(serverId: String, id: String): Flow<DownloadEntity?>

    fun observeItem(serverId: String, id: String): Flow<ItemWithDetails?> =
        observeItemRow(serverId, id).distinctUntilChanged().flatMapLatest { item ->
            if (item == null) {
                kotlinx.coroutines.flow.flowOf(null)
            } else {
                combine(
                    observeChapters(serverId, id),
                    observeAudioFiles(serverId, id),
                    observeProgress(serverId, id),
                    observeDownload(serverId, id),
                ) { chapters, files, progress, download -> ItemWithDetails(item, chapters, files, progress, download) }
            }
        }
}
