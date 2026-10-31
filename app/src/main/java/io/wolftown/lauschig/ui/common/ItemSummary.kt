package io.wolftown.lauschig.ui.common

import androidx.compose.runtime.Immutable
import okhttp3.HttpUrl

/** Everything a [CoverTile] needs; stable so unchanged tiles skip recomposition. */
@Immutable
data class ItemSummary(
    val serverId: String,
    val id: String,
    val title: String,
    val author: String?,
    val seriesName: String?,
    val seriesSequence: String?,
    /** Absolute path of the downloaded `cover.jpg`, only set when the file exists. */
    val coverPath: String?,
    /** 0f..1f */
    val progress: Float,
    val isDownloaded: Boolean,
    val isFinished: Boolean,
) {
    /** Stable lazy-list key, unique across servers. */
    val key: String get() = "$serverId:$id"
}

/**
 * Base URL of the server while online, `null` while offline. Provided once at the app root; covers are only
 * requested from the network when this is non-null, and tiles of non-downloaded items are dimmed when it is null.
 */
val LocalServerBaseUrl = androidx.compose.runtime.staticCompositionLocalOf<HttpUrl?> { null }
