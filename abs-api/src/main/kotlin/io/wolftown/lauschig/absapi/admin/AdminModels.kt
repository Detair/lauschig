package io.wolftown.lauschig.absapi.admin

import io.wolftown.lauschig.absapi.FlexibleDoubleSerializer
import kotlinx.serialization.Serializable

@Serializable
data class ItemScanResult(val result: String? = null)

/** Only the provider: ABS takes title and author from the item (like its own "match all" of a library). */
@Serializable
data class MatchRequest(val provider: String)

/** `{ "updated": true, "libraryItem": … }` or `{ "warning": "No audible.de match found" }`. */
@Serializable
data class MatchResult(val updated: Boolean = false, val warning: String? = null)

@Serializable
data class OnlineUsers(
    val usersOnline: List<OnlineUser> = emptyList(),
    val openSessions: List<OpenSession> = emptyList(),
)

@Serializable
data class OnlineUser(
    val id: String,
    val username: String,
    val type: String? = null,
    /** Their open playback session, if any. */
    val session: OpenSession? = null,
    val lastSeen: Long? = null,
    val connections: Int = 1,
)

@Serializable
data class OpenSession(
    val id: String,
    val userId: String? = null,
    val libraryItemId: String? = null,
    val episodeId: String? = null,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    @Serializable(with = FlexibleDoubleSerializer::class) val duration: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val currentTime: Double = 0.0,
    val mediaPlayer: String? = null,
    val deviceInfo: SessionDevice? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class SessionDevice(
    val clientName: String? = null,
    val clientVersion: String? = null,
    val deviceName: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val osName: String? = null,
    val browserName: String? = null,
)
