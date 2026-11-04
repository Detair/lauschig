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

/** What the statistics and achievements read of a title: names, series, duration, cover version, genres. */
data class StatsItem(
    val id: String,
    val libraryId: String,
    val title: String,
    val author: String?,
    val seriesName: String?,
    val seriesSequence: String?,
    val seriesSortKey: Double?,
    val durationSec: Double,
    val coverUpdatedAt: Long,
    val genres: List<String>,
) {
    /** As a title of [serverId] with only these columns (the rest at their defaults). */
    fun toEntity(serverId: String) = ItemEntity(
        serverId, id, libraryId, title, author = author, seriesName = seriesName, seriesSequence = seriesSequence,
        seriesSortKey = seriesSortKey, durationSec = durationSec, coverUpdatedAt = coverUpdatedAt, genres = genres,
    )
}

@Dao
interface CatalogDao {
    @Query(
        """SELECT COUNT(*) AS count, COALESCE(MAX(updatedAt), 0) AS updatedAt, COALESCE(SUM(coverUpdatedAt), 0) AS covers,
             COALESCE(SUM(addedAt), 0) AS added FROM items WHERE serverId = :serverId""",
    )
    fun observeVersion(serverId: String): Flow<CatalogVersion>

    @Query("SELECT * FROM items WHERE serverId = :serverId")
    fun observeAll(serverId: String): Flow<List<ItemEntity>>

    /** The columns of [StatsItem] only – no description, tags or narrators (read again on every catalog change). */
    @Query(
        """SELECT id, libraryId, title, author, seriesName, seriesSequence, seriesSortKey, durationSec, coverUpdatedAt, genres
             FROM items WHERE serverId = :serverId""",
    )
    fun observeForStats(serverId: String): Flow<List<StatsItem>>
}
