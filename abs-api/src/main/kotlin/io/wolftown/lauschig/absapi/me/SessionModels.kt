package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.FlexibleDoubleSerializer
import io.wolftown.lauschig.absapi.LenientListSerializer
import kotlinx.serialization.Serializable

/**
 * A listening session as the server reports it (`objects/PlaybackSession.js` `toJSON`). Times are seconds,
 * `startedAt`/`updatedAt` epoch ms. `timeListening` is an integer column but has been seen as a string.
 */
@Serializable
data class ListeningSessionDto(
    val id: String,
    val libraryItemId: String? = null,
    val episodeId: String? = null,
    val displayTitle: String? = null,
    @Serializable(with = FlexibleDoubleSerializer::class) val duration: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val timeListening: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val startTime: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val currentTime: Double = 0.0,
    val startedAt: Long,
    val updatedAt: Long,
    val deviceInfo: DeviceInfoDto? = null,
)

/** `GET /api/me/item/listening-sessions/:libraryItemId/:episodeId?`, newest `updatedAt` first. */
@Serializable
data class ListeningSessionsPage(
    val total: Int = 0,
    val numPages: Int = 0,
    val page: Int = 0,
    val itemsPerPage: Int = 0,
    @Serializable(with = LenientSessionListSerializer::class)
    val sessions: List<ListeningSessionDto> = emptyList(),
)

object LenientSessionListSerializer : LenientListSerializer<ListeningSessionDto>(ListeningSessionDto.serializer())

/**
 * One session of `GET /api/me/listening-sessions` – every session of the user, not just one title's. Like
 * [ListeningSessionDto] plus what the statistics need without the catalog: [mediaType] (`book`/`podcast`) and the
 * title and author. For an episode [displayTitle] is the episode's title and [mediaMetadata] the podcast's.
 */
@Serializable
data class UserListeningSessionDto(
    val id: String,
    val libraryItemId: String? = null,
    val episodeId: String? = null,
    val mediaType: String? = null,
    val displayTitle: String? = null,
    /** The item's authors joined as "A, B" (`metadata.authorName`; a podcast's author). */
    val displayAuthor: String? = null,
    val mediaMetadata: SessionMediaMetadataDto? = null,
    @Serializable(with = FlexibleDoubleSerializer::class) val duration: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val timeListening: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val startTime: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val currentTime: Double = 0.0,
    val startedAt: Long,
    val updatedAt: Long,
)

/** The part of a session's `mediaMetadata` (book or podcast metadata) Lauschig reads. */
@Serializable
data class SessionMediaMetadataDto(val title: String? = null)

/** `GET /api/me/listening-sessions?itemsPerPage=N&page=P` (0-based), newest `updatedAt` first (ABS 2.37.1). */
@Serializable
data class UserListeningSessionsPage(
    val total: Int = 0,
    val numPages: Int = 0,
    val page: Int = 0,
    val itemsPerPage: Int = 0,
    @Serializable(with = LenientUserSessionListSerializer::class)
    val sessions: List<UserListeningSessionDto> = emptyList(),
)

object LenientUserSessionListSerializer : LenientListSerializer<UserListeningSessionDto>(UserListeningSessionDto.serializer())
