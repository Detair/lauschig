package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.db.entities.LocalTitleEntity
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.flow.Flow

/** A folder with the number of titles found in it. */
data class LocalFolderWithCount(val uri: String, val name: String, val addedAt: Long, val lastScanAt: Long, val error: String?, val titles: Int)

/** Everything the scanner writes for one local title. */
data class LocalTitleRows(val title: LocalTitleEntity, val item: ItemEntity, val files: List<AudioFileEntity>, val chapters: List<ChapterEntity>)

/**
 * Folders and titles of the device library. Item, audio file and chapter rows of local titles live under
 * [DeviceLibrary.SERVER_ID]; only this DAO writes them. Progress, sessions and bookmarks are never deleted here.
 */
@Dao
abstract class LocalLibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addFolder(folder: LocalFolderEntity)

    @Query("SELECT * FROM local_folders ORDER BY name COLLATE NOCASE")
    abstract suspend fun folders(): List<LocalFolderEntity>

    @Query(
        """SELECT f.uri, f.name, f.addedAt, f.lastScanAt, f.error,
             (SELECT COUNT(*) FROM local_titles t WHERE t.folderUri = f.uri) AS titles
           FROM local_folders f ORDER BY f.name COLLATE NOCASE""",
    )
    abstract fun observeFolders(): Flow<List<LocalFolderWithCount>>

    @Query("UPDATE local_folders SET lastScanAt = :at, error = :error WHERE uri = :uri")
    abstract suspend fun setScanResult(uri: String, at: Long, error: String?)

    @Query("DELETE FROM local_folders WHERE uri = :uri")
    protected abstract suspend fun deleteFolderRow(uri: String)

    @Query("SELECT * FROM local_titles WHERE folderUri = :folderUri")
    abstract suspend fun titlesIn(folderUri: String): List<LocalTitleEntity>

    @Query("SELECT * FROM local_titles WHERE id = :id")
    abstract suspend fun title(id: String): LocalTitleEntity?

    @Query("SELECT addedAt FROM items WHERE serverId = :serverId AND id = :id")
    abstract suspend fun itemAddedAt(serverId: String, id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putTitle(title: LocalTitleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putItem(item: ItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putFiles(files: List<AudioFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM audio_files WHERE serverId = :serverId AND itemId = :itemId")
    protected abstract suspend fun deleteFiles(serverId: String, itemId: String)

    @Query("DELETE FROM chapters WHERE serverId = :serverId AND itemId = :itemId")
    protected abstract suspend fun deleteChapters(serverId: String, itemId: String)

    @Query("DELETE FROM items WHERE serverId = :serverId AND id = :itemId")
    protected abstract suspend fun deleteItem(serverId: String, itemId: String)

    @Query("DELETE FROM local_titles WHERE id = :id")
    protected abstract suspend fun deleteTitleRow(id: String)

    /** Replaces the rows of one title atomically. Rows of any other server are refused. */
    @Transaction
    open suspend fun putTitleRows(rows: LocalTitleRows) {
        val id = rows.item.id
        require(rows.item.serverId == DeviceLibrary.SERVER_ID && rows.title.id == id) { "not a local title" }
        require(rows.files.all { it.serverId == DeviceLibrary.SERVER_ID && it.itemId == id }) { "foreign audio file" }
        require(rows.chapters.all { it.serverId == DeviceLibrary.SERVER_ID && it.itemId == id }) { "foreign chapter" }
        deleteFiles(DeviceLibrary.SERVER_ID, id)
        deleteChapters(DeviceLibrary.SERVER_ID, id)
        putFiles(rows.files)
        putChapters(rows.chapters)
        putItem(rows.item)
        putTitle(rows.title)
    }

    /** Removes a title's catalog rows; its progress, sessions, bookmarks and speed stay (they return with the files). */
    @Transaction
    open suspend fun removeTitle(id: String) {
        deleteFiles(DeviceLibrary.SERVER_ID, id)
        deleteChapters(DeviceLibrary.SERVER_ID, id)
        deleteItem(DeviceLibrary.SERVER_ID, id)
        deleteTitleRow(id)
    }

    /** Removes the folder and the catalog rows of its titles; returns the removed title ids. */
    @Transaction
    open suspend fun removeFolder(uri: String): List<String> {
        val ids = titlesIn(uri).map { it.id }
        ids.forEach { removeTitle(it) }
        deleteFolderRow(uri)
        return ids
    }
}
