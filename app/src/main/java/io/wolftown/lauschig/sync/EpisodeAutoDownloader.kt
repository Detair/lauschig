package io.wolftown.lauschig.sync

import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.settings.AutoDownloadMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * After a sync: queues the newest N unfinished `recent` episodes of every podcast (N from the settings) through
 * the [DownloadManager], whose rules (Wi-Fi only, ...) apply as usual. Episodes that already have a download row
 * (queued, running, paused, failed, complete) or were queued by an earlier run are left alone, so a download
 * the user removed does not come back.
 */
class EpisodeAutoDownloader(
    private val mode: Flow<AutoDownloadMode>,
    private val episodes: EpisodeDao,
    private val progress: ProgressDao,
    private val downloadRows: DownloadDao,
    private val downloads: DownloadManager,
    private val queuedBefore: suspend () -> Set<String>,
    private val saveQueued: suspend (Set<String>) -> Unit,
) {
    @Inject constructor(
        settings: AppSettings, episodes: EpisodeDao, progress: ProgressDao, downloadRows: DownloadDao, downloads: DownloadManager,
    ) : this(settings.autoDownload, episodes, progress, downloadRows, downloads, settings::autoDownloaded, settings::setAutoDownloaded)

    suspend fun run(serverId: String) {
        val count = mode.first().count
        if (count <= 0) return
        val recent = episodes.recent(serverId)
        val seen = queuedBefore()
        val queued = mutableSetOf<String>()
        for ((itemId, list) in recent.groupBy { it.itemId }) {
            val newest = list.filter { progress.get(serverId, PlayableKey.episode(itemId, it.id))?.isFinished != true }.take(count)
            for (ep in newest) {
                val key = PlayableKey.episode(itemId, ep.id)
                if (key in seen || downloadRows.get(serverId, key) != null) continue
                downloads.enqueue(serverId, key)
                queued += key
            }
        }
        // Keys that dropped out of the recent set are forgotten, so the stored set stays small.
        val live = recent.mapTo(HashSet()) { PlayableKey.episode(it.itemId, it.id) }
        if (queued.isNotEmpty() || seen.any { it !in live }) saveQueued(seen.filter { it in live }.toSet() + queued)
    }
}
