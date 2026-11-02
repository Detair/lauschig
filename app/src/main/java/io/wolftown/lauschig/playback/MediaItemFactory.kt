package io.wolftown.lauschig.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.wolftown.lauschig.absapi.library.audioFileUrl
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import android.content.Context
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.local.DeviceLibrary
import okhttp3.HttpUrl
import java.io.File
import javax.inject.Inject

/** Thrown when an item is neither fully downloaded nor reachable (no [HttpUrl] given). */
class PlaybackUnavailableException(val itemId: String) : Exception("Not playable offline: $itemId")

/**
 * Builds one [MediaItem] per audio file. For an episode the item is the synthetic one of [loadPlayable]: title = episode,
 * artist (notification/lock-screen subtitle) = podcast. Streaming auth is handled by the player's data source, not here.
 * A title of the device library ([DeviceLibrary]) plays from its `content://` documents only and is never streamed.
 */
class MediaItemFactory(
    /** A file counts as local only if it still exists on disk. */
    private val fileExists: (File) -> Boolean = { it.isFile },
    /** A `content://` document (device library) counts as local only if the provider still has it. */
    private val contentExists: (Uri) -> Boolean = { true },
    private val coverFile: (serverId: String, itemId: String) -> File?,
) {
    @Inject constructor(files: DownloadFiles, @ApplicationContext context: Context) : this(
        contentExists = { uri -> documentExists(context, uri) },
        coverFile = { serverId, itemId -> try { files.playableCover(serverId, itemId) } catch (e: Exception) { null } },
    )

    /**
     * Decided per item: all files local -> only `file://` / `content://` URIs (works offline); else with [baseUrl] stream
     * every file that is not local; else [PlaybackUnavailableException]. Partial files never have a localPath. A device
     * title whose documents are gone throws even with a [baseUrl]: no server knows it.
     */
    fun build(item: ItemWithDetails, baseUrl: HttpUrl?): List<MediaItem> {
        val files = item.audioFiles.sortedBy { it.index }
        val allLocal = isFullyLocal(item)
        val deviceTitle = DeviceLibrary.isLocal(item.item.serverId, item.item.id)
        if (!allLocal && (baseUrl == null || deviceTitle)) throw PlaybackUnavailableException(item.item.id)
        val cover: Uri? = coverFile(item.item.serverId, item.item.id)?.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it) }
        return files.map { f ->
            val uri: Uri = localUri(f) ?: audioFileUrl(baseUrl!!, PlayableKey.parse(item.item.id).libraryItemId, f.ino).toString().toUri()
            val extras = Bundle().apply {
                putString(EXTRA_SERVER_ID, item.item.serverId)
                putString(EXTRA_ITEM_ID, item.item.id)
                putInt(EXTRA_FILE_INDEX, f.index)
                putLong(EXTRA_FILE_DURATION_MS, (f.durationSec * 1000).toLong())
            }
            MediaItem.Builder()
                .setMediaId("${item.item.id}#${f.index}")
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.item.title)
                        .setArtist(item.item.author)
                        .setArtworkUri(cover)
                        .setIsPlayable(true)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }

    private fun localUri(f: AudioFileEntity): Uri? {
        val path = f.localPath ?: return null
        if (path.startsWith(CONTENT_PREFIX)) return path.toUri().takeIf(contentExists)
        return File(path).takeIf(fileExists)?.let { Uri.fromFile(it) }
    }

    /** True if every audio file is completely downloaded (or a device document) and still present. */
    fun isFullyLocal(item: ItemWithDetails): Boolean = item.audioFiles.isNotEmpty() && item.audioFiles.all { localUri(it) != null }

    companion object {
        private const val CONTENT_PREFIX = "content://"

        /** One cheap provider query; false when the document is gone or the permission was revoked. */
        fun documentExists(context: Context, uri: Uri): Boolean = try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }

        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_FILE_INDEX = "fileIndex"
        /** Length of this file; lets the service step back across file boundaries (resume rewind). */
        const val EXTRA_FILE_DURATION_MS = "fileDurationMs"
    }
}
