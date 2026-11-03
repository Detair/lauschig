package io.wolftown.lauschig.auto

import android.content.res.Resources
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.wolftown.lauschig.R

/**
 * [CarEntry] → Media3 item for browsers (Android Auto, SystemUI, Assistant): title + subtitle as the description,
 * the cover as a [CoverProvider] URI, the media type, completion and download status (AA shows them as badges and
 * progress bars) and, on folders, how their children are laid out (grid for covers, list for text).
 */
@OptIn(UnstableApi::class)
class CarMediaItems(private val coverAuthority: String) {
    fun toMediaItem(e: CarEntry): MediaItem {
        val extras = Bundle()
        if (e.browsable) {
            val style = if (e.childStyle == CarEntry.Style.GRID) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, style)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, style)
        } else {
            val status = when {
                e.finished -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                e.progress > 0f -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            }
            extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
            if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) {
                extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, e.progress.toDouble())
            }
            extras.putLong(
                MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS,
                if (e.downloaded) MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED else MediaConstants.EXTRAS_VALUE_STATUS_NOT_DOWNLOADED,
            )
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(e.title)
            .setDisplayTitle(e.title)
            .setSubtitle(e.subtitle)
            .setArtworkUri(e.cover?.let { CoverUris.forTitle(coverAuthority, it) })
            .setIsBrowsable(e.browsable)
            .setIsPlayable(e.playable)
            .setMediaType(
                when (e.type) {
                    CarEntry.Type.BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                    CarEntry.Type.EPISODE -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                    CarEntry.Type.PODCAST -> MediaMetadata.MEDIA_TYPE_PODCAST
                    CarEntry.Type.SERIES -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
                    CarEntry.Type.LIBRARY -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
                    CarEntry.Type.FOLDER -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                },
            )
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(e.id).setMediaMetadata(metadata).build()
    }

    /** The root (or recent root) item; [rootHints] tell the car that content style hints are honored. */
    fun root(node: CarNode): MediaItem = MediaItem.Builder().setMediaId(MediaIds.of(node)).setMediaMetadata(
        MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
    ).build()

    companion object {
        /** Root extras: style hints are supported, lists by default (each folder states its own style). */
        fun rootHints(): Bundle = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }

        /** androidx.media's `MediaBrowserCompat` key, read by Android Auto. */
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    }
}

/** [CarTexts] from string resources (follows the device language). */
class ResourceCarTexts(private val res: Resources) : CarTexts {
    override val continueListening get() = res.getString(R.string.car_continue)
    override val downloads get() = res.getString(R.string.car_downloads)
    override val series get() = res.getString(R.string.car_series)
    override val libraries get() = res.getString(R.string.car_libraries)
    override val device get() = res.getString(R.string.library_tab_device)

    override fun remaining(sec: Double): String = res.getString(R.string.car_remaining, duration(sec))
    override fun volume(sequence: String): String = res.getString(R.string.car_volume, sequence)
    override fun volumes(count: Int): String = res.getQuantityString(R.plurals.discover_volumes, count, count)

    private fun duration(sec: Double): String {
        val minutes = (sec / 60).toLong().coerceAtLeast(1)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return if (h > 0) res.getString(R.string.car_duration_h_min, h, m) else res.getString(R.string.car_duration_min, m)
    }
}
