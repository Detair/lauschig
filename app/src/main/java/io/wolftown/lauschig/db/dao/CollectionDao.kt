package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.wolftown.lauschig.db.entities.CollectionEntity
import io.wolftown.lauschig.db.entities.CollectionEntryEntity
import io.wolftown.lauschig.db.entities.CollectionKind
import kotlinx.coroutines.flow.Flow

/** A collection/playlist with its number of titles (for the list). */
data class CollectionWithCount(
    val serverId: String,
    val id: String,
    val kind: CollectionKind,
    val libraryId: String,
    val name: String,
    val description: String?,
    val lastUpdate: Long,
    val count: Int,
)

/** Cache of the server's collections and playlists; replaced wholesale per sync. */
@Dao
abstract class CollectionDao {
    @Query(
        "SELECT c.*, (SELECT COUNT(*) FROM collection_entries e WHERE e.serverId = c.serverId AND e.collectionId = c.id) AS count " +
            "FROM collections c WHERE c.serverId = :serverId AND c.kind = :kind ORDER BY c.name COLLATE NOCASE",
    )
    abstract fun observeByKind(serverId: String, kind: CollectionKind): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE serverId = :serverId AND id = :id")
    abstract fun observe(serverId: String, id: String): Flow<CollectionEntity?>

    @Query("SELECT * FROM collection_entries WHERE serverId = :serverId AND collectionId = :id ORDER BY position")
    abstract fun observeEntries(serverId: String, id: String): Flow<List<CollectionEntryEntity>>

    @Query("SELECT * FROM collection_entries WHERE serverId = :serverId AND collectionId = :id ORDER BY position")
    abstract suspend fun entries(serverId: String, id: String): List<CollectionEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertCollections(rows: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEntries(rows: List<CollectionEntryEntity>)

    @Query(
        "DELETE FROM collection_entries WHERE serverId = :serverId AND collectionId IN " +
            "(SELECT id FROM collections WHERE serverId = :serverId AND kind = 'COLLECTION' AND libraryId = :libraryId)",
    )
    protected abstract suspend fun deleteCollectionEntries(serverId: String, libraryId: String)

    @Query("DELETE FROM collections WHERE serverId = :serverId AND kind = 'COLLECTION' AND libraryId = :libraryId")
    protected abstract suspend fun deleteCollections(serverId: String, libraryId: String)

    @Query(
        "DELETE FROM collection_entries WHERE serverId = :serverId AND collectionId IN " +
            "(SELECT id FROM collections WHERE serverId = :serverId AND kind = 'PLAYLIST')",
    )
    protected abstract suspend fun deletePlaylistEntries(serverId: String)

    @Query("DELETE FROM collections WHERE serverId = :serverId AND kind = 'PLAYLIST'")
    protected abstract suspend fun deletePlaylists(serverId: String)

    /** The collections of one library as fetched; [entries] keyed by collection id in server order. */
    @Transaction
    open suspend fun replaceCollections(serverId: String, libraryId: String, rows: List<CollectionEntity>, entries: Map<String, List<String>>) {
        deleteCollectionEntries(serverId, libraryId)
        deleteCollections(serverId, libraryId)
        insertCollections(rows)
        insertEntries(rows.flatMap { c -> entries[c.id].orEmpty().mapIndexed { i, k -> CollectionEntryEntity(serverId, c.id, i, k) } })
    }

    /** All playlists of the user as fetched. */
    @Transaction
    open suspend fun replacePlaylists(serverId: String, rows: List<CollectionEntity>, entries: Map<String, List<String>>) {
        deletePlaylistEntries(serverId)
        deletePlaylists(serverId)
        insertCollections(rows)
        insertEntries(rows.flatMap { c -> entries[c.id].orEmpty().mapIndexed { i, k -> CollectionEntryEntity(serverId, c.id, i, k) } })
    }
}
