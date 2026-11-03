package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Query
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.Flow

/** Read-only catalog queries; entity pages, search and discover evaluate the lists in `Catalog`. */
/**
 * A fingerprint of one server's titles: changes when a title is added, removed or replaced, updated on the server, or
 * gets a new cover. Lets watchers (the car's browse tree) react without loading every title.
 */
data class CatalogVersion(val count: Int, val updatedAt: Long, val covers: Long, val added: Long)

@Dao
interface CatalogDao {
    @Query(
        """SELECT COUNT(*) AS count, COALESCE(MAX(updatedAt), 0) AS updatedAt, COALESCE(SUM(coverUpdatedAt), 0) AS covers,
             COALESCE(SUM(addedAt), 0) AS added FROM items WHERE serverId = :serverId""",
    )
    fun observeVersion(serverId: String): Flow<CatalogVersion>

    @Query("SELECT * FROM items WHERE serverId = :serverId")
    fun observeAll(serverId: String): Flow<List<ItemEntity>>
}
