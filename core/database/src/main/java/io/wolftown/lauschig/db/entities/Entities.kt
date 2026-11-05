package io.wolftown.lauschig.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "chapters", primaryKeys = ["serverId", "itemId", "index"])
data class ChapterEntity(
    val serverId: String,
    val itemId: String,
    val index: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

@Entity(tableName = "audio_files", primaryKeys = ["serverId", "itemId", "index"])
data class AudioFileEntity(
    val serverId: String,
    val itemId: String,
    val index: Int,
    val ino: String,
    val durationSec: Double,
    val sizeBytes: Long,
    val ext: String,
    /** Non-null only once the file is completely downloaded. */
    val localPath: String? = null,
    val completedBytes: Long = 0,
)

@Entity(tableName = "progress", primaryKeys = ["serverId", "itemId"], indices = [Index("serverId", "isFinished", "lastUpdate")])
data class ProgressEntity(
    val serverId: String,
    val itemId: String,
    val currentTimeSec: Double,
    val durationSec: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
    val dirty: Boolean = false,
    /** Hidden from "continue listening" (ABS `hideFromContinueListening`); listening again clears it. */
    @ColumnInfo(defaultValue = "0") val hideFromContinue: Boolean = false,
)

@Entity(tableName = "local_sessions", indices = [Index("serverId", "synced")], primaryKeys = ["id"])
data class LocalSessionEntity(
    val id: String,
    val serverId: String,
    val itemId: String,
    val startTimeSec: Double,
    val currentTimeSec: Double,
    val timeListeningSec: Double,
    val startedAt: Long,
    val updatedAt: Long,
    val synced: Boolean = false,
)

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED }

@Entity(tableName = "downloads", primaryKeys = ["serverId", "itemId"], indices = [Index("serverId", "state")])
data class DownloadEntity(
    val serverId: String,
    val itemId: String,
    val state: DownloadState,
    val requestedAt: Long,
    val error: String? = null,
)
