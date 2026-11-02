package io.wolftown.lauschig.queue

import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.playback.PlayableKey

/** Whether a queued title can start right now. */
enum class QueueEntryState {
    PLAYABLE,

    /** Known, but neither downloaded nor reachable (offline): stays queued for later. */
    UNAVAILABLE_NOW,

    /** Not in the library (any more), or only an orphan without a download: can never play, leaves the queue. */
    MISSING,
}

object AdvancePolicy {
    fun classify(exists: Boolean, orphaned: Boolean, downloaded: Boolean, online: Boolean): QueueEntryState = when {
        !exists -> QueueEntryState.MISSING
        orphaned && !downloaded -> QueueEntryState.MISSING
        downloaded || online -> QueueEntryState.PLAYABLE
        else -> QueueEntryState.UNAVAILABLE_NOW
    }
}

/** [AdvancePolicy.classify] from Room: a book needs its item row, an episode its podcast and episode rows. */
suspend fun queueEntryState(items: ItemDao, episodes: EpisodeDao, downloads: DownloadDao, online: Boolean, serverId: String, key: String): QueueEntryState {
    val p = PlayableKey.parse(key)
    val item = items.get(serverId, p.libraryItemId)
    val exists = item != null && (p.episodeId == null || episodes.get(serverId, p.libraryItemId, p.episodeId) != null)
    val downloaded = downloads.get(serverId, key)?.state == DownloadState.COMPLETE
    return AdvancePolicy.classify(exists, item?.orphaned == true, downloaded, online)
}

/**
 * Starts the next queued title when one played to its end. Runs inside the playback service (on its player's
 * `STATE_ENDED`), so it never depends on the app's MediaController being connected.
 *
 * The queue wins over the next-volume suggestion: when an entry starts, the suggestion only remains on the finished
 * title. The ended title leaves the queue if it was queued too. Entries are tried in order: a [QueueEntryState.MISSING]
 * one is dropped, one that fails to start is dropped, and the next one is tried; an [QueueEntryState.UNAVAILABLE_NOW]
 * one (offline, not downloaded) is skipped but stays for later. Nothing starts while the sleep timer waits for the end
 * of a chapter (the listener wants to stop); with nothing playable the regular end of a title applies.
 */
class QueueAdvancer(
    private val entries: suspend (serverId: String) -> List<String>,
    private val remove: suspend (serverId: String, key: String) -> Unit,
    private val check: suspend (serverId: String, key: String) -> QueueEntryState,
    private val sleepEndOfChapter: () -> Boolean,
    /** Loads and plays [key]; false if it could not be started. */
    private val start: suspend (serverId: String, key: String) -> Boolean,
) {
    /** Returns the key that was started, or null. */
    suspend fun onEnded(serverId: String, endedKey: String): String? {
        val queue = entries(serverId)
        if (endedKey in queue) remove(serverId, endedKey)
        if (sleepEndOfChapter()) return null
        for (key in queue) {
            if (key == endedKey) continue
            when (check(serverId, key)) {
                QueueEntryState.MISSING -> remove(serverId, key)
                QueueEntryState.UNAVAILABLE_NOW -> Unit
                QueueEntryState.PLAYABLE -> {
                    remove(serverId, key)
                    if (start(serverId, key)) return key
                }
            }
        }
        return null
    }
}
