package io.wolftown.lauschig.db.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * Podcast episode. Progress and downloads of an episode are keyed by its playable key (`itemId~id`,
 * see `PlayableKey`), not by this row.
 */
@Entity(tableName = "episodes", primaryKeys = ["serverId", "itemId", "id"], indices = [Index("serverId", "publishedAt")])
data class EpisodeEntity(
    val serverId: String,
    val itemId: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val season: String? = null,
    val episode: String? = null,
    /** Epoch ms; 0 when the server omits it. */
    val publishedAt: Long = 0,
    val durationSec: Double = 0.0,
    val sizeBytes: Long = 0,
    /** Listed in the library's recent (unheard) episodes. */
    val recent: Boolean = false,
)
