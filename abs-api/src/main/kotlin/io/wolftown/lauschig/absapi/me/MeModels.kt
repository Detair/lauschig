package io.wolftown.lauschig.absapi.me

import kotlinx.serialization.Serializable

/** `MediaProgress.getOldMediaProgress()`; `lastUpdate` is epoch millis. Shared by `AbsUser` and `GET /api/me`. */
@Serializable
data class MediaProgressDto(
    val libraryItemId: String,
    val episodeId: String? = null,
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    val lastUpdate: Long,
)
