package io.wolftown.lauschig.db.entities

import androidx.room.Entity
import androidx.room.Index

/** Outbox state of a bookmark: what still has to reach the server. */
enum class BookmarkPending { NONE, UPSERT, DELETE }

/**
 * A bookmark of one playable ([itemKey] = book id or `itemId~episodeId`) at [timeSec]. Identity is the time, as on
 * the server. [note] is optional (the server title is derived from it, see `BookmarkRules`). A row with
 * [pending] = DELETE is a tombstone: hidden in the UI, removed once the server confirmed the delete.
 * [onServer]: the server is known to have this bookmark. [rev] grows with every local change, so a push only
 * completes a row that did not change while the request was in flight. Episode bookmarks never leave the device.
 */
@Entity(tableName = "bookmarks", primaryKeys = ["serverId", "itemKey", "timeSec"])
data class BookmarkEntity(
    val serverId: String,
    val itemKey: String,
    val timeSec: Double,
    val note: String? = null,
    val createdAt: Long = 0,
    val pending: BookmarkPending = BookmarkPending.NONE,
    val onServer: Boolean = false,
    val rev: Long = 0,
)

/** "Play next" queue of this device; [position] is 0-based and dense. */
@Entity(tableName = "queue", primaryKeys = ["serverId", "itemKey"])
data class QueueEntity(
    val serverId: String,
    val itemKey: String,
    val position: Int,
    val addedAt: Long,
)

/** A listening session of a title as the server reported it (cache for the history; times in seconds, epoch ms). */
@Entity(tableName = "history_sessions", primaryKeys = ["serverId", "id"], indices = [Index("serverId", "itemKey")])
data class HistorySessionEntity(
    val serverId: String,
    val id: String,
    val itemKey: String,
    val startedAt: Long,
    val updatedAt: Long,
    val startTimeSec: Double,
    val currentTimeSec: Double,
    val timeListeningSec: Double,
    val deviceName: String? = null,
)

enum class CollectionKind { COLLECTION, PLAYLIST }

/** An ABS collection (books of one library) or one of the user's playlists (books or episodes). */
@Entity(tableName = "collections", primaryKeys = ["serverId", "id"])
data class CollectionEntity(
    val serverId: String,
    val id: String,
    val kind: CollectionKind,
    val libraryId: String,
    val name: String,
    val description: String? = null,
    val lastUpdate: Long = 0,
)

/** One title of a collection/playlist in server order; [itemKey] is a playable key. */
@Entity(tableName = "collection_entries", primaryKeys = ["serverId", "collectionId", "position"])
data class CollectionEntryEntity(
    val serverId: String,
    val collectionId: String,
    val position: Int,
    val itemKey: String,
)
