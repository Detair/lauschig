package io.wolftown.lauschig.absapi.me

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** `MediaProgress.getOldMediaProgress()`; `lastUpdate` is epoch millis. Shared by `AbsUser` and `GET /api/me`. */
@Serializable
data class MediaProgressDto(
    /** Null for rows the server could not attribute to an item. */
    val libraryItemId: String? = null,
    val episodeId: String? = null,
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
)

/** Body of `PATCH /api/me/progress/:libraryItemId`. */
@Serializable
data class ProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
)

/**
 * A listening session recorded on the device, uploaded via `POST /api/session/local-all`.
 * Times are seconds, `startedAt`/`updatedAt` epoch millis. The server creates the row from this JSON
 * (`PlaybackSession.getFromOld`) and fills library/book ids itself.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LocalSessionDto(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaType: String,
    val displayTitle: String,
    val duration: Double,
    val startTime: Double,
    val currentTime: Double,
    val timeListening: Double,
    val startedAt: Long,
    val updatedAt: Long,
    val deviceInfo: DeviceInfoDto,
    val displayAuthor: String? = null,
    /** "YYYY-MM-DD" of the session; the server uses it for listening statistics. */
    val date: String? = null,
    val dayOfWeek: String? = null,
    /** 3 = PlayMethod.LOCAL. */
    @EncodeDefault val playMethod: Int = PLAY_METHOD_LOCAL,
    @EncodeDefault val mediaPlayer: String = "lauschig",
) {
    companion object {
        const val PLAY_METHOD_LOCAL = 3
    }
}

/** Body of `POST /api/session/local-all`; the server reads the device from the top-level `deviceInfo`. */
@Serializable
data class LocalSessionsBody(
    val sessions: List<LocalSessionDto>,
    val deviceInfo: DeviceInfoDto? = null,
)

/** Client-supplied fields of the server's `DeviceInfo`. */
@Serializable
data class DeviceInfoDto(
    val deviceId: String? = null,
    val clientName: String? = null,
    val clientVersion: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val sdkVersion: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class LocalSessionsResult(val results: List<LocalSessionResult>)

@Serializable
data class LocalSessionResult(
    val id: String,
    val success: Boolean,
    val progressSynced: Boolean? = null,
    val error: String? = null,
)
