package io.wolftown.lauschig.history

import androidx.compose.runtime.Immutable
import io.wolftown.lauschig.db.entities.HistorySessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity

/** One listening session of a title: when, how long, from where to where (book-wide seconds). */
@Immutable
data class HistoryEntry(
    val id: String,
    val startedAt: Long,
    val startSec: Double,
    val endSec: Double,
    val listenedSec: Double,
    /** Device name as the server knows it; null for sessions only known locally. */
    val deviceName: String?,
    /** Recorded on this device (synced or not). */
    val thisDevice: Boolean,
)

@Immutable
data class History(val entries: List<HistoryEntry>, val totalListenedSec: Double)

/**
 * Merges the cached server sessions of a title with the sessions recorded on this device. Same id = same session
 * (local sessions are uploaded under their own id): the local row wins while it is not synced (it is the freshest),
 * afterwards the server row (it knows the device). Sessions under [MIN_LISTENED_SEC] are hidden. Newest first.
 */
object HistoryAggregation {
    const val MIN_LISTENED_SEC = 5.0

    fun build(server: List<HistorySessionEntity>, local: List<LocalSessionEntity>): History {
        val localById = local.associateBy { it.id }
        val byId = LinkedHashMap<String, HistoryEntry>()
        for (s in server) {
            val l = localById[s.id]
            byId[s.id] = if (l != null && !l.synced) l.toEntry() else s.toEntry(thisDevice = l != null)
        }
        for (l in local) if (l.id !in byId) byId[l.id] = l.toEntry()
        val entries = byId.values.filter { it.listenedSec >= MIN_LISTENED_SEC }.sortedByDescending { it.startedAt }
        return History(entries, entries.sumOf { it.listenedSec })
    }

    private fun HistorySessionEntity.toEntry(thisDevice: Boolean) =
        HistoryEntry(id, startedAt, startTimeSec, currentTimeSec, timeListeningSec, deviceName, thisDevice)

    private fun LocalSessionEntity.toEntry() =
        HistoryEntry(id, startedAt, startTimeSec, currentTimeSec, timeListeningSec, deviceName = null, thisDevice = true)
}
