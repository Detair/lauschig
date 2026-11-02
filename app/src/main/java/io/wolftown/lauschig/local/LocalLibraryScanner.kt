package io.wolftown.lauschig.local

import android.util.Log
import io.wolftown.lauschig.db.dao.LocalLibraryDao
import io.wolftown.lauschig.db.dao.LocalTitleRows
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.db.entities.LocalTitleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Reads the picked folders into the device library (rules in the phase-3C plan and [LocalGrouping] / [LocalMeta]). Only
 * titles whose files changed are read again ([LocalTitleEntity.signature]). A folder that cannot be read keeps its
 * titles and records why; a title that vanished from a readable folder loses its catalog rows (not its progress).
 * One scan at a time per process. Never touches the network.
 */
class LocalLibraryScanner(
    private val tree: DocumentTree,
    private val probe: AudioProbe,
    private val dao: LocalLibraryDao,
    private val covers: LocalCovers,
    private val clock: () -> Long,
) {
    @Inject constructor(tree: SafDocumentTree, probe: RetrieverAudioProbe, dao: LocalLibraryDao, covers: LocalCovers) :
        this(tree, probe, dao, covers, System::currentTimeMillis)

    /** Scans every folder; returns the number of titles found. */
    suspend fun scanAll(): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            var n = 0
            for (f in dao.folders()) {
                coroutineContext.ensureActive()
                n += try {
                    scanFolder(f)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One broken folder (a provider that throws) must not stop the others; its titles stay.
                    Log.w(TAG, "scan of a folder failed", e)
                    dao.setScanResult(f.uri, clock(), LocalFolderEntity.ERROR_MISSING)
                    0
                }
            }
            n
        }
    }

    /** Removes the folder and its titles' catalog rows and covers; returns the removed ids. */
    suspend fun removeFolder(uri: String): List<String> = lock.withLock {
        withContext(Dispatchers.IO) { dao.removeFolder(uri).onEach(covers::delete) }
    }

    private suspend fun scanFolder(folder: LocalFolderEntity): Int {
        val now = clock()
        when (tree.access(folder.uri)) {
            TreeAccess.NO_ACCESS -> { dao.setScanResult(folder.uri, now, LocalFolderEntity.ERROR_NO_ACCESS); return 0 }
            TreeAccess.MISSING -> { dao.setScanResult(folder.uri, now, LocalFolderEntity.ERROR_MISSING); return 0 }
            TreeAccess.OK -> Unit
        }
        val rootEntry = tree.root(folder.uri)
        val rootKids = rootEntry?.let { tree.children(folder.uri, it) }
        if (rootEntry == null || rootKids == null) {
            dao.setScanResult(folder.uri, now, LocalFolderEntity.ERROR_MISSING)
            return 0
        }
        val budget = intArrayOf(MAX_ENTRIES)
        val root = node(folder.uri, rootEntry, "", rootKids, depth = 0, budget)
        val groups = LocalGrouping.group(root, folder.name)
        val existing = dao.titlesIn(folder.uri).associateBy { it.id }
        val seen = HashSet<String>()
        for (g in groups) {
            coroutineContext.ensureActive()
            val id = DeviceLibrary.titleId(folder.uri, g.groupKey)
            val signature = signature(g)
            if (existing[id]?.signature == signature) { seen += id; continue }
            if (store(folder.uri, id, g, signature, now)) seen += id
        }
        for (id in existing.keys - seen) {
            dao.removeTitle(id)
            covers.delete(id)
        }
        dao.setScanResult(folder.uri, now, null)
        return seen.size
    }

    /** Reads the files of one title and writes its rows; false if none of them is playable. */
    private suspend fun store(treeUri: String, id: String, g: TitleGroup, signature: String, now: Long): Boolean {
        val uris = g.files.map { tree.documentUri(treeUri, it.documentId) }
        val meta = LocalMeta.build(g, uris.map(probe::tags)) ?: return false
        val firstUri = tree.documentUri(treeUri, meta.files.first().entry.documentId)
        val picture = probe.embeddedPicture(firstUri)?.takeIf { it.size in 1..MAX_COVER_BYTES }
            ?: g.covers.firstNotNullOfOrNull { tree.readBytes(tree.documentUri(treeUri, it.documentId), MAX_COVER_BYTES)?.takeIf { b -> b.isNotEmpty() } }
        val hasCover = picture != null && covers.write(id, picture)
        if (!hasCover) covers.delete(id)
        val dev = DeviceLibrary.SERVER_ID
        val item = ItemEntity(
            serverId = dev,
            id = id,
            libraryId = DeviceLibrary.LIBRARY_ID,
            title = meta.title,
            author = meta.author,
            narrator = meta.narrator,
            durationSec = meta.durationSec,
            coverUpdatedAt = if (hasCover) now else 0,
            updatedAt = now,
            addedAt = dao.itemAddedAt(dev, id) ?: now,
            detailsLoaded = true,
        )
        val files = meta.files.mapIndexed { i, f ->
            AudioFileEntity(
                serverId = dev, itemId = id, index = i + 1, ino = f.entry.documentId, durationSec = f.durationSec,
                sizeBytes = f.entry.size, ext = LocalGrouping.extension(f.entry.name),
                localPath = tree.documentUri(treeUri, f.entry.documentId), completedBytes = f.entry.size,
            )
        }
        val chapters = meta.chapters.mapIndexed { i, c -> ChapterEntity(dev, id, i, c.startSec, c.endSec, c.title) }
        dao.putTitleRows(LocalTitleRows(LocalTitleEntity(id, treeUri, g.groupKey, g.relativePath, signature, now), item, files, chapters))
        return true
    }

    /** Lists [entry]'s subtree (depth and entry count are capped, so a huge or looping provider cannot hang the scan). */
    private fun node(treeUri: String, entry: DocEntry, path: String, kids: List<DocEntry>, depth: Int, budget: IntArray): DirNode {
        budget[0] -= kids.size
        val files = kids.filter { !it.isDirectory }
        val dirs = if (depth >= MAX_DEPTH) emptyList() else kids.filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { d ->
            if (budget[0] <= 0) return@mapNotNull null
            val sub = tree.children(treeUri, d) ?: return@mapNotNull null
            node(treeUri, d, if (path.isEmpty()) d.name else "$path/${d.name}", sub, depth + 1, budget)
        }
        return DirNode(entry, path, files, dirs)
    }

    private fun signature(g: TitleGroup): String {
        val md = MessageDigest.getInstance("SHA-256")
        (g.files + g.covers).forEach { md.update("${it.documentId}|${it.size}|${it.lastModified}\n".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private companion object {
        const val TAG = "LocalScanner"
        const val MAX_DEPTH = 8
        const val MAX_ENTRIES = 20_000
        const val MAX_COVER_BYTES = 8 * 1024 * 1024
        /** Process-wide: the worker and an on-demand scan never write the same rows at once. */
        val lock = Mutex()
    }
}
