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
