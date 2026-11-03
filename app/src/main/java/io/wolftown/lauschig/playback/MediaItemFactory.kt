package io.wolftown.lauschig.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.wolftown.lauschig.absapi.library.audioFileUrl
import io.wolftown.lauschig.auto.CoverProvider
import io.wolftown.lauschig.auto.CoverRef
import io.wolftown.lauschig.auto.CoverUris
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
    /**
     * The artwork other apps can open (Android Auto, SystemUI, Bluetooth): a [CoverProvider] URI for the title's cover
     * version; null falls back to [coverFile] (only this app can read that one).
     */
    private val artworkUri: (serverId: String, key: String, version: Long) -> Uri? = { _, _, _ -> null },
    private val coverFile: (serverId: String, itemId: String) -> File?,
) {
    @Inject constructor(files: DownloadFiles, @ApplicationContext context: Context) : this(
        contentExists = { uri -> documentExists(context, uri) },
        artworkUri = { serverId, key, version -> CoverUris.forTitle(CoverUris.authority(context.packageName), CoverRef(serverId, key, version)) },
        coverFile = { serverId, itemId -> try { files.playableCover(serverId, itemId) } catch (e: Exception) { null } },
    )

    /**
     * Decided per item: all files local -> only `file://` / `content://` URIs (works offline); else with [baseUrl] stream
     * every file that is not local; else [PlaybackUnavailableException]. Partial files never have a localPath. A device
     * title whose documents are gone throws even with a [baseUrl]: no server knows it.
     */
    fun build(item: ItemWithDetails, baseUrl: HttpUrl?): List<MediaItem> {
        val files = item.audioFiles.sortedBy { it.index }
        // Resolved once: a file that vanishes while this runs must not make the two decisions disagree.
        val local = resolveLocal(files)
        val allLocal = files.isNotEmpty() && local.all { it != null }
        val deviceTitle = DeviceLibrary.isLocal(item.item.serverId, item.item.id)
        if (!allLocal && (baseUrl == null || deviceTitle)) throw PlaybackUnavailableException(item.item.id)
        val cover: Uri? = artworkUri(item.item.serverId, item.item.id, item.item.coverUpdatedAt)
            ?: coverFile(item.item.serverId, item.item.id)?.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it) }
        val episode = PlayableKey.parse(item.item.id).episodeId != null
        return files.mapIndexed { i, f ->
            val uri: Uri = local[i]
                ?: audioFileUrl(baseUrl ?: throw PlaybackUnavailableException(item.item.id), PlayableKey.parse(item.item.id).libraryItemId, f.ino).toString().toUri()
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
                        // Description for cars and the system media controls; the chapter replaces the subtitle
                        // while playing (ChapterTracker).
                        .setDisplayTitle(item.item.title)
                        .setSubtitle(item.item.author)
                        .setMediaType(if (episode) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                        .setArtworkUri(cover)
                        .setIsPlayable(true)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }

    /**
     * The local URI of every file (null = not local), in order. `file://` paths are checked one by one (cheap). Of the
     * `content://` documents (device library) only the first is asked for at the provider: they come from one picked
     * folder under one permission, so it stands for all of them (a single deleted file fails at play time instead,
     * and the next scan drops it). A provider query costs an IPC round trip; a title can have hundreds of files.
     */
    private fun resolveLocal(files: List<AudioFileEntity>): List<Uri?> {
        var contentPresent: Boolean? = null
        return files.map { f ->
            val path = f.localPath ?: return@map null
            if (path.startsWith(CONTENT_PREFIX)) {
                val uri = path.toUri()
                val present = contentPresent ?: contentExists(uri).also { contentPresent = it }
                if (present) uri else null
            } else {
                File(path).takeIf(fileExists)?.let { Uri.fromFile(it) }
            }
        }
    }

    /** True if every audio file is completely downloaded (or a device document) and still present. */
    fun isFullyLocal(item: ItemWithDetails): Boolean = item.audioFiles.isNotEmpty() && resolveLocal(item.audioFiles).all { it != null }

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
