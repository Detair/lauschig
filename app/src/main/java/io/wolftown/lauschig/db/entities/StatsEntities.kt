package io.wolftown.lauschig.db.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * A listening session of the account as the server reported it (`GET /api/me/listening-sessions`), every device's:
 * the source of the listening statistics. [itemKey] is a playable key (book id or `itemId~episodeId`; empty when the
 * server no longer knows the item); [title] is the item's (a book's, an episode's podcast), [author] as the server
 * joins it ("A, B"). Times are seconds, [startedAt]/[updatedAt] epoch ms. A session that keeps growing on the server
 * replaces its row.
 */
@Entity(tableName = "listening_sessions", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "startedAt")])
data class ListeningSessionEntity(
    val serverId: String,
    val id: String,
    val itemKey: String,
    val title: String?,
    val author: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val timeListeningSec: Double,
    val durationSec: Double,
    val currentTimeSec: Double,
)
