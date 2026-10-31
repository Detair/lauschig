package io.wolftown.lauschig.ui.common

import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles

/** Builds the tile model; does a file-system check, so call it off the main thread. */
fun ItemEntity.toSummary(files: DownloadFiles, progress: ProgressEntity?, downloaded: Boolean): ItemSummary {
    val duration = progress?.durationSec?.takeIf { it > 0 } ?: durationSec
    val fraction = when {
        progress == null -> 0f
        progress.isFinished -> 1f
        duration > 0 -> (progress.currentTimeSec / duration).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    return ItemSummary(
        serverId, id, title, author, seriesName, seriesSequence,
        coverPath = if (downloaded) localCover(files, serverId, id) else null,
        progress = fraction,
        isDownloaded = downloaded,
        isFinished = progress?.isFinished == true,
    )
}

private fun localCover(files: DownloadFiles, serverId: String, id: String): String? = try {
    files.coverFile(serverId, id).takeIf { it.isFile && it.length() > 0 }?.path
} catch (_: Exception) {
    null // invalid ids have no directory
}
