package io.wolftown.lauschig.auto

import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.ServiceTitleStarter

/**
 * Turns what a car, the Assistant or SystemUI asks the session to play into the player queue of ONE title, with the
 * same rules as the app ([ServiceTitleStarter], rewind after a long pause): a browsed `title/…` id, a play-from-search
 * query, or playback resumption (the last title on this device). Items that already carry a URI come from the app's
 * own controller and are never touched ([needsResolution]).
 */
class CarStarts(
    private val resolveTitle: suspend (serverId: String, key: String, rewind: Boolean) -> ServiceTitleStarter.Resolved,
    private val searchTitle: suspend (query: String, hints: SearchHints) -> CarNode.Title?,
    /** The title last played on this device (`LastTitleStarter.last()`), null if none may be resumed. */
    private val lastTitle: suspend () -> CarNode.Title?,
    /** The signed-in account's server (null when signed out: then only device titles play). */
    private val accountServer: suspend () -> String?,
) {
    sealed interface Outcome {
        data class Ready(val title: CarNode.Title, val items: List<MediaItem>, val index: Int, val offsetMs: Long, val streaming: Boolean) : Outcome
        data class Failed(val reason: Reason) : Outcome
    }

    enum class Reason { NOTHING_FOUND, NOT_FOUND, OFFLINE, LOCAL_UNAVAILABLE, NOT_PLAYABLE, NOTHING_TO_RESUME }

    /** False when every item has a URI (the app's own queue, its in-place replacements): pass them through. */
    fun needsResolution(items: List<MediaItem>): Boolean = items.any { it.localConfiguration == null }

    /**
     * The request item at [startIndex] (else the first) decides; the rest of a car list is never queued (the player
     * queue holds the files of one title, later titles come from Lauschig's own queue).
     */
    suspend fun forRequest(items: List<MediaItem>, startIndex: Int): Outcome {
        val item = items.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex) ?: items.firstOrNull()
            ?: return Outcome.Failed(Reason.NOT_FOUND)
        val query = item.requestMetadata.searchQuery
        val title = if (query != null) {
            searchTitle(query, hints(item.requestMetadata.extras)) ?: return Outcome.Failed(Reason.NOTHING_FOUND)
        } else {
            (MediaIds.parse(item.mediaId) as? CarNode.Title)?.takeIf { allowed(it) } ?: return Outcome.Failed(Reason.NOT_FOUND)
        }
        return start(title)
    }

    suspend fun forResumption(): Outcome = start(lastTitle() ?: return Outcome.Failed(Reason.NOTHING_TO_RESUME))

    /** A title of the signed-in account's server or of this device; ids of another (earlier) account never play. */
    private suspend fun allowed(t: CarNode.Title): Boolean = DeviceLibrary.isLocal(t.serverId) || t.serverId == accountServer()

    private suspend fun start(title: CarNode.Title): Outcome = when (val r = resolveTitle(title.serverId, title.key, true)) {
        is ServiceTitleStarter.Resolved.Ready -> Outcome.Ready(title, r.items, r.index, r.offsetMs, r.streaming)
        is ServiceTitleStarter.Resolved.Failed -> Outcome.Failed(
            when (r.reason) {
                ServiceTitleStarter.Failure.NOT_FOUND -> Reason.NOT_FOUND
                ServiceTitleStarter.Failure.OFFLINE -> Reason.OFFLINE
                ServiceTitleStarter.Failure.LOCAL_UNAVAILABLE -> Reason.LOCAL_UNAVAILABLE
                ServiceTitleStarter.Failure.NOT_PLAYABLE -> Reason.NOT_PLAYABLE
            },
        )
    }

    companion object {
        /** What the Assistant understood besides the raw query (`MediaStore.EXTRA_MEDIA_*`). */
        fun hints(extras: Bundle?): SearchHints = if (extras == null) SearchHints() else SearchHints(
            title = extras.getString(MediaStore.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() },
            artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)?.takeIf { it.isNotBlank() },
            album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)?.takeIf { it.isNotBlank() },
        )
    }
}
