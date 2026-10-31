package io.wolftown.lauschig.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.dao.AudioFileDao
import java.io.File
import javax.inject.Inject

/** On-disk layout: `<root>/<serverId>/<itemId>/<index>.<ext>` (+ `.part` while downloading) and `cover.jpg`. */
class DownloadFiles(private val root: File, private val audioFiles: AudioFileDao) {
    @Inject constructor(@ApplicationContext context: Context, audioFiles: AudioFileDao) :
        this(File(context.filesDir, "downloads"), audioFiles)

    fun itemDir(serverId: String, itemId: String) = File(File(root, serverId), itemId)

    fun audioFile(serverId: String, itemId: String, index: Int, ext: String) =
        File(itemDir(serverId, itemId), if (ext.isBlank()) "$index" else "$index.$ext")

    fun coverFile(serverId: String, itemId: String) = File(itemDir(serverId, itemId), COVER)

    fun deleteItem(serverId: String, itemId: String) {
        itemDir(serverId, itemId).deleteRecursively()
    }

    /**
     * Deletes everything in the item dir that no [io.wolftown.lauschig.db.entities.AudioFileEntity.localPath]
     * references (files that vanished or were replaced on the server, stale `.part` files). The cover is kept.
     */
    suspend fun cleanupOrphans(serverId: String, itemId: String) {
        val dir = itemDir(serverId, itemId)
        val children = dir.listFiles() ?: return
        val keep = audioFiles.forItem(serverId, itemId).mapNotNull { it.localPath }.map { File(it).canonicalPath }.toSet()
        for (f in children) {
            if (f.name == COVER) continue
            if (f.canonicalPath !in keep) f.deleteRecursively()
        }
    }

    companion object {
        const val COVER = "cover.jpg"
    }
}
