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
 * the [DownloadManager], whose rules (Wi-Fi only, ...) apply as usual. Every episode in the window is remembered once handled
 * (queued here or already with a download row), so a download the user removed does not come back.
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
        // Remembered as "serverId:key". Keys that dropped out of the recent set are forgotten, so the stored set stays small;
        // other servers' keys are left alone. Old unprefixed keys are migrated when still live, otherwise dropped.
        val prefix = "$serverId:"
        val live = recent.mapTo(HashSet()) { PlayableKey.episode(it.itemId, it.id) }
        val before = queuedBefore()
        val handled = HashSet<String>()
        for (k in before) {
            when {
                k.startsWith(prefix) -> if (k.removePrefix(prefix) in live) handled += k
                ':' in k -> handled += k // another server's key
                k in live -> handled += prefix + k // legacy unprefixed key
            }
        }
        if (handled != before) saveQueued(handled.toSet())
        for ((itemId, list) in recent.groupBy { it.itemId }) {
            val window = list.filter { progress.get(serverId, PlayableKey.episode(itemId, it.id))?.isFinished != true }.take(count)
            for (ep in window) {
                val key = PlayableKey.episode(itemId, ep.id)
                if (prefix + key in handled) continue
                // Every key in the window counts as handled, whether queued here or already having a row (e.g. a manual
                // download): if the user removes it later, it must not come back.
                if (downloadRows.get(serverId, key) == null) downloads.enqueue(serverId, key)
                handled += prefix + key
                saveQueued(handled.toSet()) // after each enqueue, so a crash never re-queues what was already handled
            }
        }
    }
}
