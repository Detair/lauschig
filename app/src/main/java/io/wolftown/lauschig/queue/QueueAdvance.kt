package io.wolftown.lauschig.queue

import io.wolftown.lauschig.local.DeviceLibrary
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

/**
 * Whose queue goes on after a title of [endedServerId] ended: the signed-in [account]'s (a device title plays inside
 * it). Null when signed out or when the title belongs to another server (a sign-out or account switch in between): the
 * service then never starts a title of a server that is not the current account.
 */
fun queueOwner(endedServerId: String, account: String?): String? = when {
    account == null -> null
    DeviceLibrary.isLocal(endedServerId) || endedServerId == account -> account
    else -> null
}

object AdvancePolicy {
    fun classify(exists: Boolean, orphaned: Boolean, downloaded: Boolean, online: Boolean): QueueEntryState = when {
        !exists -> QueueEntryState.MISSING
        orphaned && !downloaded -> QueueEntryState.MISSING
        downloaded || online -> QueueEntryState.PLAYABLE
        else -> QueueEntryState.UNAVAILABLE_NOW
    }
}

/**
 * [AdvancePolicy.classify] from Room: a book needs its item row, an episode its podcast and episode rows. [serverId] is
 * the queue's (the account's); a device title's key resolves to the device library and counts as downloaded.
 */
suspend fun queueEntryState(items: ItemDao, episodes: EpisodeDao, downloads: DownloadDao, online: Boolean, serverId: String, key: String): QueueEntryState {
    val p = PlayableKey.parse(key)
    val sid = DeviceLibrary.serverFor(serverId, key) ?: serverId
    val item = items.get(sid, p.libraryItemId)
    val exists = item != null && (p.episodeId == null || episodes.get(sid, p.libraryItemId, p.episodeId) != null)
    val downloaded = DeviceLibrary.isLocal(sid) || downloads.get(sid, key)?.state == DownloadState.COMPLETE
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
    /** False once [serverId] is no longer the signed-in account (a sign-out came in between): nothing more starts. */
    private val stillOwner: suspend (serverId: String) -> Boolean = { true },
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
                    if (!stillOwner(serverId)) return null
                    remove(serverId, key)
                    if (start(serverId, key)) return key
                }
            }
        }
        return null
    }
}
