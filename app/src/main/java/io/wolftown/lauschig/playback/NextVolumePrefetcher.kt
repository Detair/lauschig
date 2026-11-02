package io.wolftown.lauschig.playback

import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.settings.NextVolumeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * With [NextVolumeMode.PREFETCH]: once [THRESHOLD] of a series volume has been heard, queues the download of the
 * next volume (once per title and app run). The download itself follows the normal rules (Wi-Fi only etc.).
 */
@Singleton
class NextVolumePrefetcher @Inject constructor(
    private val player: PlayerController,
    private val settings: AppSettings,
    private val accounts: AccountStore,
    private val items: ItemDao,
    private val downloadsDao: DownloadDao,
    private val downloads: DownloadManager,
) {
    private val handled = HashSet<String>()

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(player.state, settings.nextVolumeMode) { s, mode ->
                if (mode == NextVolumeMode.PREFETCH && s.itemId != null && s.durationSec > 0 && s.positionSec / s.durationSec >= THRESHOLD) s.itemId else null
            }.distinctUntilChanged().collect { itemId -> if (itemId != null) prefetchAfter(itemId) }
        }
    }

    private suspend fun prefetchAfter(itemId: String) {
        if (PlayableKey.parse(itemId).episodeId != null) return // episodes have no next volume
        val serverId = accounts.account.first()?.serverId ?: return
        if (!handled.add("$serverId:$itemId")) return
        val next = nextVolume(items, serverId, itemId) ?: return
        if (downloadsDao.get(serverId, next.id) != null) return // already queued, downloaded or deliberately paused
        downloads.enqueue(serverId, next.id)
    }

    companion object {
        const val THRESHOLD = 0.8
    }
}

/** The next volume after [itemId] in its series, or null (no series, no sequence, last volume). */
suspend fun nextVolume(items: ItemDao, serverId: String, itemId: String) = items.get(serverId, itemId)?.let { cur ->
    val name = cur.seriesName?.takeIf { it.isNotBlank() } ?: return@let null
    val key = cur.seriesSortKey ?: return@let null
    items.nextInSeries(serverId, name, key)
}
