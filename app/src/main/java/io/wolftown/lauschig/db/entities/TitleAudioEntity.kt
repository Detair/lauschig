package io.wolftown.lauschig.db.entities

import androidx.room.Entity

/**
 * Device-local audio settings of one playable (book id or `itemId~episodeId`). Null = use the default from the
 * playback settings. Not synced: ABS has no field for it.
 */
@Entity(tableName = "title_audio", primaryKeys = ["serverId", "itemId"])
data class TitleAudioEntity(
    val serverId: String,
    val itemId: String,
    val speed: Float? = null,
    val boostDb: Int? = null,
)
