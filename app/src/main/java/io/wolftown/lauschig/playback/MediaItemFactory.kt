package io.wolftown.lauschig.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.wolftown.lauschig.absapi.library.audioFileUrl
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.download.DownloadFiles
import okhttp3.HttpUrl
import java.io.File
import javax.inject.Inject

/** Thrown when an item is neither fully downloaded nor reachable (no [HttpUrl] given). */
class PlaybackUnavailableException(val itemId: String) : Exception("Not playable offline: $itemId")

/**
 * Builds one [MediaItem] per audio file. For an episode the item is the synthetic one of [loadPlayable]: title = episode,
 * artist (notification/lock-screen subtitle) = podcast. Streaming auth is handled by the player's data source, not here.
 */
class MediaItemFactory(
    /** A file counts as local only if it still exists on disk. */
    private val fileExists: (File) -> Boolean = { it.isFile },
    private val coverFile: (serverId: String, itemId: String) -> File?,
) {
    @Inject constructor(files: DownloadFiles) : this(coverFile = { serverId, itemId ->
        try { files.playableCover(serverId, itemId) } catch (e: Exception) { null }
    })

    /**
     * Decided per item: all files local -> only `file://` URIs (works offline); else with [baseUrl] stream
     * every file that is not local; else [PlaybackUnavailableException]. Partial files never have a localPath.
     */
    fun build(item: ItemWithDetails, baseUrl: HttpUrl?): List<MediaItem> {
        val files = item.audioFiles.sortedBy { it.index }
        val allLocal = isFullyLocal(item)
        if (!allLocal && baseUrl == null) throw PlaybackUnavailableException(item.item.id)
        val cover: Uri? = coverFile(item.item.serverId, item.item.id)?.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it) }
        return files.map { f ->
            val uri: Uri = localFile(f)?.let { Uri.fromFile(it) } ?: audioFileUrl(baseUrl!!, PlayableKey.parse(item.item.id).libraryItemId, f.ino).toString().toUri()
            val extras = Bundle().apply {
                putString(EXTRA_SERVER_ID, item.item.serverId)
                putString(EXTRA_ITEM_ID, item.item.id)
                putInt(EXTRA_FILE_INDEX, f.index)
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

    private fun localFile(f: AudioFileEntity): File? = f.localPath?.let { File(it) }?.takeIf(fileExists)

    /** True if every audio file is completely downloaded and still present on disk. */
    fun isFullyLocal(item: ItemWithDetails): Boolean = item.audioFiles.isNotEmpty() && item.audioFiles.all { localFile(it) != null }

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_FILE_INDEX = "fileIndex"
    }
}
