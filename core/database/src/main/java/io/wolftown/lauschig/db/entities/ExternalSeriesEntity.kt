package io.wolftown.lauschig.db.entities

import androidx.room.Entity

/** Cached Audible volume list of one series ([json] = serialised `List<AudibleVolume>`); [fetchedAt] is epoch ms. */
@Entity(tableName = "external_series", primaryKeys = ["serverId", "libraryId", "seriesName"])
data class ExternalSeriesEntity(
    val serverId: String,
    val libraryId: String,
    val seriesName: String,
    val json: String,
    val fetchedAt: Long,
)
