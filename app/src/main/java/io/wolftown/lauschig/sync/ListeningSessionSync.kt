package io.wolftown.lauschig.sync

import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.UserListeningSessionDto
import io.wolftown.lauschig.db.dao.ListeningSessionDao
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches every listening session of the account (`GET /api/me/listening-sessions`, newest `updatedAt` first) in Room,
 * the source of the listening statistics.
 *
 * - The first run pages through everything ([FULL_PAGE] per page, at most [MAX_PAGES] pages).
 * - A later run first asks for the newest session alone: when it is not newer than the last complete run's newest and
 *   the server's total is unchanged, nothing changed and the run ends there. Otherwise it pages ([LATER_PAGE] per page)
 *   until a page reaches sessions updated before that newest minus [OVERLAP_MS] (sessions another device uploads late
 *   keep their old time), or the last page.
 *
 * Each page is written as it arrives (one page in memory; an interrupted run keeps what it got). Only a complete run
 * moves the stamps, so a failure starts over next time. Offline, and for the device library, nothing happens. Runs of one
 * process are serialized (worker and screen may ask at the same time).
 */
@Singleton
class ListeningSessionSync(
    private val api: Flow<MeApi?>,
    private val dao: ListeningSessionDao,
    private val stamps: SyncStamps,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(apiProvider: ApiProvider, dao: ListeningSessionDao, stamps: SyncStamps) :
        this(apiProvider.meApi, dao, stamps, System::currentTimeMillis)

    private val lock = Mutex()

    /** Background variant: at most once per [MIN_INTERVAL_MS] after a complete run (a failure does not count). */
    suspend fun syncIfDue(serverId: String) {
        val last = stamps.get(runKey(serverId))
        if (last != null && clock() - last in 0 until MIN_INTERVAL_MS) return
        sync(serverId)
    }

    /** Fetches now (the statistics page asks on open); a failure throws and keeps the cache. */
    suspend fun sync(serverId: String): Unit = lock.withLock {
        if (DeviceLibrary.isLocal(serverId)) return
        val api = api.first() ?: return
        val since = stamps.get(newestKey(serverId))
        if (since != null) {
            val probe = api.listeningSessions(itemsPerPage = 1, page = 0)
            val top = probe.sessions.firstOrNull()?.updatedAt ?: 0L
            if (top <= since && probe.total.toLong() == stamps.get(totalKey(serverId))) {
                stamps.set(runKey(serverId), clock())
                return
            }
        }
        val cutoff = since?.minus(OVERLAP_MS)
        val size = if (since == null) FULL_PAGE else LATER_PAGE
        var newest = since ?: 0L
        var total: Int? = null
        var page = 0
        while (page < MAX_PAGES) {
            val result = api.listeningSessions(itemsPerPage = size, page = page)
            if (total == null) total = result.total
            val sessions = result.sessions
            if (sessions.isNotEmpty()) dao.upsertAll(sessions.map { it.toEntity(serverId) })
            newest = maxOf(newest, sessions.maxOfOrNull { it.updatedAt } ?: 0L)
            page++
            if (sessions.isEmpty() || page >= result.numPages) break
            if (cutoff != null && sessions.minOf { it.updatedAt } < cutoff) break
        }
        stamps.set(newestKey(serverId), newest)
        stamps.set(totalKey(serverId), (total ?: 0).toLong())
        stamps.set(runKey(serverId), clock())
    }

    companion object {
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val OVERLAP_MS = 24 * 60 * 60 * 1000L
        const val FULL_PAGE = 100
        const val LATER_PAGE = 20

        /** Safety cap for one run: 50,000 sessions on a first run. */
        const val MAX_PAGES = 500

        // Every key ends with ":<serverId>", so sign-out forgets them with the account (SyncStamps.removeServer).
        private fun newestKey(serverId: String) = "listening_sessions:$serverId"
        private fun totalKey(serverId: String) = "listening_sessions_total:$serverId"
        private fun runKey(serverId: String) = "listening_sessions_run:$serverId"
    }
}

/**
 * The row of one session: [ListeningSessionEntity.itemKey] is the playable key (empty when the server no longer names
 * the item); the title is the item's – a book's, or for an episode its podcast's – as the statistics count items.
 */
internal fun UserListeningSessionDto.toEntity(serverId: String): ListeningSessionEntity {
    val item = libraryItemId?.takeIf { it.isNotBlank() }
    val episode = episodeId?.takeIf { it.isNotBlank() }
    val key = when {
        item == null -> ""
        episode != null -> PlayableKey.episode(item, episode)
        else -> item
    }
    val title = if (episode != null) mediaMetadata?.title else displayTitle ?: mediaMetadata?.title
    return ListeningSessionEntity(
        serverId = serverId, id = id, itemKey = key,
        title = title?.takeIf { it.isNotBlank() }, author = displayAuthor?.takeIf { it.isNotBlank() },
        startedAt = startedAt, updatedAt = updatedAt,
        timeListeningSec = timeListening, durationSec = duration, currentTimeSec = currentTime,
    )
}
