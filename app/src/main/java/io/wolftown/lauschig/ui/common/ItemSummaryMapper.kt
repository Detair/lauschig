package io.wolftown.lauschig.ui.common

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Resolves local cover paths with one file-system check per downloaded item, remembered until the set of
 * downloaded items changes ([sync]); progress ticks therefore never touch the disk.
 */
class CoverPathCache(
    private val files: DownloadFiles,
    private val check: (File) -> Boolean,
) {
    @Inject constructor(files: DownloadFiles) : this(files, { it.isFile && it.length() > 0 })

    private val cache = ConcurrentHashMap<String, String>() // "" = checked, no cover
    private var lastSynced: Set<String>? = null

    /** Forgets everything when the downloaded set differs from the last call (covers may have appeared or gone). */
    fun sync(downloadedKeys: Set<String>) {
        if (downloadedKeys != lastSynced) {
            cache.clear()
            lastSynced = downloadedKeys
        }
    }

    fun pathFor(serverId: String, id: String): String? =
        cache.getOrPut("$serverId:$id") {
            try {
                files.coverFile(serverId, id).takeIf(check)?.path ?: ""
            } catch (_: Exception) {
                "" // invalid ids have no directory
            }
        }.ifEmpty { null }
}

/** Builds the tile model; [covers] may hit the file system once per item, so call it off the main thread. */
fun ItemEntity.toSummary(covers: CoverPathCache, progress: ProgressEntity?, downloaded: Boolean): ItemSummary {
    val duration = progress?.durationSec?.takeIf { it > 0 } ?: durationSec
    val fraction = when {
        progress == null -> 0f
        progress.isFinished -> 1f
        duration > 0 -> (progress.currentTimeSec / duration).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    return ItemSummary(
        serverId, id, title, author, seriesName, seriesSequence,
        coverPath = if (downloaded) covers.pathFor(serverId, id) else null,
        progress = fraction,
        isDownloaded = downloaded,
        isFinished = progress?.isFinished == true,
        coverVersion = coverUpdatedAt,
    )
}
