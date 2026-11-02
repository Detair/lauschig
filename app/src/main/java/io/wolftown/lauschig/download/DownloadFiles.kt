package io.wolftown.lauschig.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.local.LocalCovers
import io.wolftown.lauschig.playback.PlayableKey
import java.io.File
import javax.inject.Inject

/**
 * On-disk layout: `<root>/<serverId>/<itemId>/<index>.<ext>` (+ `.part` while downloading) and `cover.jpg`. Titles of the
 * device library are never downloaded; only their covers live in app storage ([deviceCovers], see [LocalCovers]).
 */
class DownloadFiles(
    private val root: File,
    private val audioFiles: AudioFileDao,
    /** Seam for tests: free bytes on the volume holding the given directory. */
    private val usableSpace: (File) -> Long = { dir -> generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L },
    deviceCovers: File = File(root.absoluteFile.parentFile, LocalCovers.DIR),
) {
    private val localCovers = LocalCovers(deviceCovers)

    @Inject constructor(@ApplicationContext context: Context, audioFiles: AudioFileDao) :
        this(File(context.filesDir, "downloads"), audioFiles)

    /** Server-controlled ids are validated; anything else is refused (never retried). */
    fun itemDir(serverId: String, itemId: String): File {
        if (!SERVER_ID.matches(serverId) || !ITEM_ID.matches(itemId)) throw FatalDownloadException("invalid_path")
        return guard(File(File(root, serverId), itemId))
    }

    /** The extension is only used if it is 1-8 alphanumerics; otherwise the file has none. */
    fun audioFile(serverId: String, itemId: String, index: Int, ext: String): File =
        guard(File(itemDir(serverId, itemId), if (EXT.matches(ext)) "$index.$ext" else "$index"))

    /** A device title's cover is the one its scan copied into app storage. */
    fun coverFile(serverId: String, itemId: String): File =
        if (DeviceLibrary.isLocal(serverId)) localCovers.file(itemId) ?: throw FatalDownloadException("invalid_path")
        else guard(File(itemDir(serverId, itemId), COVER))

    /** Cover shown for a playable key: its own downloaded cover, else (episodes) the podcast's. */
    fun playableCover(serverId: String, key: String): File {
        if (DeviceLibrary.isLocal(serverId)) return coverFile(serverId, key)
        val own = coverFile(serverId, key)
        if (own.isFile) return own
        return coverFile(serverId, PlayableKey.parse(key).libraryItemId)
    }

    fun usableSpace(serverId: String, itemId: String): Long = usableSpace(itemDir(serverId, itemId))

    /** Free bytes on the volume that holds the downloads (shown on the download management page). Blocking. */
    fun freeSpace(): Long = usableSpace(root)

    /** Defense in depth: every target must resolve (symlinks included) to below the downloads root. */
    private fun guard(f: File): File {
        val canonicalRoot = root.canonicalFile
        if (!f.canonicalFile.startsWith(canonicalRoot) || f.canonicalFile == canonicalRoot) throw FatalDownloadException("invalid_path")
        return f
    }

    /** Never deletes outside the root; invalid ids have no directory, so there is nothing to delete. */
    fun deleteItem(serverId: String, itemId: String) {
        val dir = try { itemDir(serverId, itemId) } catch (e: FatalDownloadException) { return }
        dir.deleteRecursively()
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
            if (f.canonicalPath !in keep) guard(f).deleteRecursively()
        }
    }

    companion object {
        const val COVER = "cover.jpg"
        private val SERVER_ID = Regex("[0-9a-f]{16}")
        private val ITEM_ID = Regex("[A-Za-z0-9_-]{1,128}(~[A-Za-z0-9_-]{1,128})?") // book id or episode key
        private val EXT = Regex("[A-Za-z0-9]{1,8}")
    }
}

/** Unrecoverable download error; [code] is a short machine code stored in `DownloadEntity.error`. */
class FatalDownloadException(val code: String) : Exception(code)
