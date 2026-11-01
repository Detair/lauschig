package io.wolftown.lauschig.db.dao

import androidx.room.Dao
import androidx.room.Query
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.Flow

/** Read-only catalog queries; entity pages, search and discover evaluate the lists in `Catalog`. */
@Dao
interface CatalogDao {
    @Query("SELECT * FROM items WHERE serverId = :serverId")
    fun observeAll(serverId: String): Flow<List<ItemEntity>>
}
