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
 *
 * Downloads may live on several volumes: the internal [root] and the download roots of other volumes ([roots], e.g. an SD
 * card). A title stays where its directory is; a title without one goes to [DownloadRoots.target] (internal if none).
 * When a title has a directory on more than one volume (re-downloaded while its card was out), the internal one wins,
 * then the others in the system's order. Everything that walks all downloads (sign-out size, purge, janitor) covers every
 * volume that is mounted.
 */
class DownloadFiles(
    private val root: File,
    private val audioFiles: AudioFileDao,
    /** Seam for tests: free bytes on the volume holding the given directory. */
    private val usableSpace: (File) -> Long = { dir -> generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L },
    deviceCovers: File = File(root.absoluteFile.parentFile, LocalCovers.DIR),
    private val roots: DownloadRoots = DownloadRoots.None,
) {
    private val localCovers = LocalCovers(deviceCovers)

    @Inject constructor(@ApplicationContext context: Context, audioFiles: AudioFileDao, roots: VolumeDownloadRoots) :
        this(File(context.filesDir, DownloadVolume.DOWNLOADS), audioFiles, roots = roots)

    /** The internal root first, then the mounted other volumes' roots (never twice). */
    private fun allRoots(): List<File> {
        val seen = HashSet<String>()
        return (listOf(root) + roots.external()).filter { seen.add(it.absolutePath) }
    }

    /** Where a title without a directory goes. */
    private fun placement(): File = roots.target() ?: root

    /** Suspends until new downloads go where the setting says (a worker started with the app process waits for it). */
    suspend fun awaitLocation() = roots.awaitReady()

    /** The directories [itemId] has on the mounted volumes (usually one, none if not downloaded). */
    private fun existingItemDirs(serverId: String, itemId: String): List<File> =
        allRoots().map { File(File(it, serverId), itemId) }.filter { it.isDirectory }.map { guard(it) }

    /** Server-controlled ids are validated; anything else is refused (never retried). */
    fun itemDir(serverId: String, itemId: String): File {
        if (!SERVER_ID.matches(serverId) || !ITEM_ID.matches(itemId)) throw FatalDownloadException("invalid_path")
        return existingItemDirs(serverId, itemId).firstOrNull() ?: guard(File(File(placement(), serverId), itemId))
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

    /** Free bytes on the volume new downloads go to (shown on the download management page). Blocking. */
    fun freeSpace(): Long = usableSpace(placement())

    /** Defense in depth: every target must resolve (symlinks included) to below one of the download roots. */
    private fun guard(f: File): File {
        val canonical = f.canonicalFile
        val inside = (allRoots() + placement()).any { r -> val c = r.canonicalFile; canonical.startsWith(c) && canonical != c }
        if (!inside) throw FatalDownloadException("invalid_path")
        return f
    }

    /** The directory of [serverId]'s downloads on every mounted volume; only a real server id (the device library has none). */
    private fun serverDirs(serverId: String): List<File> {
        if (!SERVER_ID.matches(serverId)) throw FatalDownloadException("invalid_path")
        return allRoots().map { guard(File(it, serverId)) }
    }

    /**
     * Bytes on disk of [serverId]'s downloads on every mounted volume: complete and partial files and covers. 0 for none
     * or an invalid id. Blocking.
     */
    fun serverBytes(serverId: String): Long {
        val dirs = try { serverDirs(serverId) } catch (e: FatalDownloadException) { return 0 }
        return dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    /**
     * Deletes every download of [serverId] (sign-out) on every mounted volume, partial ones included; never anything
     * outside its directories. False if something could not be deleted (an invalid id has no directory: true). A card
     * that is out cannot be reached: what it holds goes when it is back ([io.wolftown.lauschig.account.AccountDataJanitor]).
     * Blocking.
     */
    fun deleteServer(serverId: String): Boolean {
        val dirs = try { serverDirs(serverId) } catch (e: FatalDownloadException) { return true }
        return dirs.map { it.deleteRecursively() }.all { it }
    }

    /**
     * The download roots of [serverId]'s recorded files that are not mounted now (an SD card that is out), from the
     * files' paths in Room. Blocking.
     */
    suspend fun missingRoots(serverId: String): Set<String> {
        if (!SERVER_ID.matches(serverId)) return emptySet()
        val mounted = allRoots().map { it.absolutePath }
        return audioFiles.localPaths(serverId).filter { it.startsWith("/") && "/$serverId/" in it }
            .filter { p -> mounted.none { r -> p.startsWith("$r/") } }
            .map { it.substringBefore("/$serverId/") }.toSet()
    }

    /**
     * Deletes [serverId]'s directory on the download root [root] if that is a mounted volume's root now: true when it
     * is gone, false if not everything could be deleted, null if [root] is not mounted (never a path outside a root).
     * Blocking.
     */
    fun deleteServerAt(root: String, serverId: String): Boolean? {
        if (!SERVER_ID.matches(serverId)) return true
        val r = roots.external().firstOrNull { it.absolutePath == root } ?: return null
        val dir = try { guard(File(r, serverId)) } catch (e: FatalDownloadException) { return null }
        return dir.deleteRecursively()
    }

    /** The server ids that have a download directory on any mounted volume (only real ids; the device library has none). Blocking. */
    fun serverIds(): List<String> =
        allRoots().flatMap { r -> r.listFiles { f -> f.isDirectory && SERVER_ID.matches(f.name) }?.map { it.name }.orEmpty() }.distinct().sorted()

    /** The item keys of [serverId] that have a directory on any mounted volume; only valid ids. Blocking. */
    fun itemIds(serverId: String): List<String> {
        val dirs = try { serverDirs(serverId) } catch (e: FatalDownloadException) { return emptyList() }
        return dirs.flatMap { d -> d.listFiles { f -> f.isDirectory && ITEM_ID.matches(f.name) }?.map { it.name }.orEmpty() }.distinct().sorted()
    }

    /** True if [itemId] has directories on more than one volume (see the class comment). Blocking. */
    fun isOnSeveralVolumes(serverId: String, itemId: String): Boolean =
        SERVER_ID.matches(serverId) && ITEM_ID.matches(itemId) &&
            (try { existingItemDirs(serverId, itemId) } catch (e: FatalDownloadException) { emptyList() }).size > 1

    /** Deletes the title's directory on every mounted volume; invalid ids have none, so there is nothing to delete. */
    fun deleteItem(serverId: String, itemId: String) {
        if (!SERVER_ID.matches(serverId) || !ITEM_ID.matches(itemId)) return
        val dirs = try { existingItemDirs(serverId, itemId) } catch (e: FatalDownloadException) { return }
        dirs.forEach { it.deleteRecursively() }
    }

    /**
     * Deletes everything in the title's directories that no [io.wolftown.lauschig.db.entities.AudioFileEntity.localPath]
     * references (files that vanished or were replaced on the server, stale `.part` files, a copy left on another volume).
     * The cover is kept in the directory [itemDir] uses; another volume's directory goes once it is empty.
     */
    suspend fun cleanupOrphans(serverId: String, itemId: String) {
        val home = itemDir(serverId, itemId)
        val dirs = existingItemDirs(serverId, itemId)
        if (dirs.isEmpty()) return
        val keep = audioFiles.forItem(serverId, itemId).mapNotNull { it.localPath }.map { File(it).canonicalPath }.toSet()
        for (dir in dirs) {
            val children = dir.listFiles() ?: continue
            val isHome = dir.absolutePath == home.absolutePath
            for (f in children) {
                if (isHome && f.name == COVER) continue
                if (f.canonicalPath !in keep) guard(f).deleteRecursively()
            }
            if (!isHome && dir.list()?.isEmpty() == true) dir.delete()
        }
    }

    /**
     * When the download of [itemId] was completed, on the device's clock: the newest modification time of its recorded
     * audio files (a file is written, then renamed into place). Null when nothing is recorded or a recorded file is not
     * there (a card that is out). Blocking.
     */
    suspend fun completedAt(serverId: String, itemId: String): Long? {
        val paths = audioFiles.forItem(serverId, itemId).map { it.localPath ?: return null }
        if (paths.isEmpty()) return null
        return paths.maxOf { p -> File(p).takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 } ?: return null }
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
