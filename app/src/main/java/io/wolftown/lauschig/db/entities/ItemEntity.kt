package io.wolftown.lauschig.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "items", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "libraryId")])
data class ItemEntity(
    val serverId: String,
    val id: String,
    val libraryId: String,
    val title: String,
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    /** Only known once details are loaded (list sync uses minified items). */
    val seriesId: String? = null,
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    val seriesSortKey: Double? = null,
    val durationSec: Double = 0.0,
    val coverUpdatedAt: Long = 0,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val updatedAt: Long = 0,
    /** Epoch ms the item was added to the library on the server. */
    @ColumnInfo(defaultValue = "0") val addedAt: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val publishedYear: String? = null,
    val detailsLoaded: Boolean = false,
    /** Removed on the server but kept because it has a download or progress. */
    val orphaned: Boolean = false,
)
