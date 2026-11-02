package io.wolftown.lauschig.playback

import android.util.Log
import androidx.media3.common.MediaItem
import io.wolftown.lauschig.db.dao.ItemWithDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Loads a title straight into the service's own player (no MediaController round trip), for the queue's
 * auto-advance: same source rules as [PlayerControllerImpl.play] (local files, else the online server; details are
 * fetched if the files are not known yet), starting at the saved position unless the title is finished.
 */
class ServiceTitleStarter(
    private val loadItem: suspend (serverId: String, key: String) -> ItemWithDetails?,
    private val factory: MediaItemFactory,
    /** The server streams come from; null while offline. */
    private val baseUrl: () -> HttpUrl?,
    private val loadDetails: suspend (serverId: String, key: String) -> Unit,
    private val io: CoroutineDispatcher,
    /** Replaces the player's queue and plays (main thread). */
    private val setQueue: (items: List<MediaItem>, index: Int, offsetMs: Long) -> Unit,
    private val onStreamStarted: (serverId: String, key: String) -> Unit = { _, _ -> },
) {
    suspend fun start(serverId: String, key: String): Boolean {
        var item = loadItem(serverId, key) ?: return false
        val allLocal = withContext(io) { factory.isFullyLocal(item) }
        val base = if (allLocal) null else baseUrl() ?: return false
        if (item.audioFiles.isEmpty()) {
            item = try {
                loadDetails(serverId, key)
                loadItem(serverId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loading details for the next title failed", e)
                null
            }?.takeIf { it.audioFiles.isNotEmpty() } ?: return false
        }
        val items = try {
            withContext(io) { factory.build(item, base) }
        } catch (e: PlaybackUnavailableException) {
            return false
        }
        val start = item.progress?.takeUnless { it.isFinished }?.currentTimeSec?.coerceAtLeast(0.0) ?: 0.0
        val (index, offsetMs) = TimelineMapper(item.audioFiles).toFile(start)
        setQueue(items, index, offsetMs)
        if (!allLocal) onStreamStarted(serverId, key)
        return true
    }

    private companion object {
        const val TAG = "ServiceTitleStarter"
    }
}
