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
    /** Download queued or running. */
    val isDownloading: Boolean = false,
    /** Server `coverUpdatedAt`; appended to the cover URL so a changed cover bypasses the image cache. */
    val coverVersion: Long = 0,
) {
    /** Stable lazy-list key, unique across servers. */
    val key: String get() = "$serverId:$id"
}

/**
 * Base URL of the server while online, `null` while offline. Provided once at the app root; covers are only
 * requested from the network when this is non-null.
 */
val LocalServerBaseUrl = androidx.compose.runtime.staticCompositionLocalOf<HttpUrl?> { null }

/** True only once offline has settled (see `rememberSettledOffline`); drives dimming, never the cover source. */
val LocalOfflineDimming = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The app-wide snackbar host (null outside the app frame, e.g. in tests). */
val LocalSnackbarHost = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.material3.SnackbarHostState?> { null }
