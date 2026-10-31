package io.wolftown.lauschig.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.wolftown.lauschig.absapi.library.audioFileUrl
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.download.DownloadFiles
import okhttp3.HttpUrl
import java.io.File
import javax.inject.Inject

/** Thrown when an item is neither fully downloaded nor reachable (no [HttpUrl] given). */
class PlaybackUnavailableException(val itemId: String) : Exception("Not playable offline: $itemId")

/** Builds one [MediaItem] per audio file. Streaming auth is handled by the player's data source, not here. */
class MediaItemFactory(private val coverFile: (serverId: String, itemId: String) -> File?) {
    @Inject constructor(files: DownloadFiles) : this({ serverId, itemId ->
        try { files.coverFile(serverId, itemId) } catch (e: Exception) { null }
    })

    /**
     * Decided per item: all files local -> only `file://` URIs (works offline); else with [baseUrl] stream
     * every file that is not local; else [PlaybackUnavailableException]. Partial files never have a localPath.
     */
    fun build(item: ItemWithDetails, baseUrl: HttpUrl?): List<MediaItem> {
        val files = item.audioFiles.sortedBy { it.index }
        val allLocal = files.isNotEmpty() && files.all { it.localPath != null }
        if (!allLocal && baseUrl == null) throw PlaybackUnavailableException(item.item.id)
        val cover: Uri? = coverFile(item.item.serverId, item.item.id)?.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it) }
        return files.map { f ->
            val uri: Uri = f.localPath?.let { Uri.fromFile(File(it)) } ?: audioFileUrl(baseUrl!!, item.item.id, f.ino).toString().toUri()
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

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_FILE_INDEX = "fileIndex"
    }
}
