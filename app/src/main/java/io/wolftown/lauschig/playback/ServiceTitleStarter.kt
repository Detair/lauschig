package io.wolftown.lauschig.playback

import io.wolftown.lauschig.cleanup.TitleHandover
import io.wolftown.lauschig.diagnostics.Log
import androidx.media3.common.MediaItem
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

/**
 * Loads a title straight into the service's own player (no MediaController round trip): the queue's auto-advance,
 * Android Auto, voice and playback resumption. Same source rules as [PlayerControllerImpl.play] (local files, else the
 * online server; details are fetched if the files are not known yet), starting at the saved position unless the title
 * is finished; with `rewind` also the app's jump back after a long pause ([rewindAfterPause], from the progress'
 * `lastUpdate`), because the service's own resume rewind cannot see a pause that outlived the player.
 */
class ServiceTitleStarter(
    private val loadItem: suspend (serverId: String, key: String) -> ItemWithDetails?,
    private val factory: MediaItemFactory,
    /** The server streams come from; null while offline. */
    private val baseUrl: suspend () -> HttpUrl?,
    private val loadDetails: suspend (serverId: String, key: String) -> Unit,
    private val io: CoroutineDispatcher,
    /** Replaces the player's queue and plays (main thread). */
    private val setQueue: (items: List<MediaItem>, index: Int, offsetMs: Long) -> Unit,
    private val onStreamStarted: (serverId: String, key: String) -> Unit = { _, _ -> },
    /** Ms to jump back after a pause of the given length (see [rewindMs]); only for `rewind` starts. */
    private val rewindAfterPause: suspend (pauseMs: Long) -> Long = { 0 },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A title ready for the player ([index], [offsetMs] = the start), or why it cannot play. */
    sealed interface Resolved {
        data class Ready(val items: List<MediaItem>, val index: Int, val offsetMs: Long, val streaming: Boolean) : Resolved
        data class Failed(val reason: Failure) : Resolved
    }

    enum class Failure {
        /** Unknown title (or its podcast/episode row is gone). */
        NOT_FOUND,
        /** Not (completely) on the device and no server online. */
        OFFLINE,
        /** A device title whose documents are gone or no longer readable. */
        LOCAL_UNAVAILABLE,
        /** Online, but the server knows no files for it (details failed or empty). */
        NOT_PLAYABLE,
    }

    /** Builds the queue of [key] without touching the player. */
    suspend fun resolve(serverId: String, key: String, rewind: Boolean = false): Resolved {
        var item = loadItem(serverId, key) ?: return Resolved.Failed(Failure.NOT_FOUND)
        val deviceTitle = DeviceLibrary.isLocal(serverId, key)
        val allLocal = withContext(io) { factory.isFullyLocal(item) }
        // A device title plays from its documents or not at all: no server, no details, no download on play.
        if (!allLocal && deviceTitle) return Resolved.Failed(Failure.LOCAL_UNAVAILABLE)
        val base = if (allLocal) null else baseUrl() ?: return Resolved.Failed(Failure.OFFLINE)
        if (item.audioFiles.isEmpty()) {
            item = try {
                loadDetails(serverId, key)
                loadItem(serverId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loading details for the title failed", e)
                null
            }?.takeIf { it.audioFiles.isNotEmpty() } ?: return Resolved.Failed(Failure.NOT_PLAYABLE)
        }
        val items = try {
            withContext(io) { factory.build(item, base) }
        } catch (e: PlaybackUnavailableException) {
            return Resolved.Failed(if (deviceTitle) Failure.LOCAL_UNAVAILABLE else Failure.OFFLINE)
        }
        val start = item.progress?.takeUnless { it.isFinished }?.let { p ->
            when {
                p.currentTimeSec <= 0 -> 0.0
                rewind -> (p.currentTimeSec - rewindAfterPause(clock() - p.lastUpdate) / 1000.0).coerceAtLeast(0.0)
                else -> p.currentTimeSec
            }
        } ?: 0.0
        val (index, offsetMs) = TimelineMapper(item.audioFiles).toFile(start)
        return Resolved.Ready(items, index, offsetMs, streaming = !allLocal)
    }

    /**
     * Resolves [key] and plays it on the service's player (queue advance); false if it cannot play. The resolve can wait a
     * round trip for the details: [stillAllowed] is asked after it, and if the account it was for has been left meanwhile
     * nothing plays and nothing else starts for it (no download either).
     */
    suspend fun start(serverId: String, key: String, stillAllowed: suspend () -> Boolean = { true }): Boolean {
        TitleHandover.pick(key) // a queue start: held for the automatic clean-up while it loads (car starts: CarStarts)
        val r = resolve(serverId, key) as? Resolved.Ready ?: return false
        if (!stillAllowed()) return false
        setQueue(r.items, r.index, r.offsetMs)
        if (r.streaming) onStreamStarted(serverId, key)
        return true
    }

    private companion object {
        const val TAG = "ServiceTitleStarter"
    }
}
