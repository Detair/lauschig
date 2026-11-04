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
 * - A **full run** pages through everything ([FULL_PAGE] per page, at most [MAX_PAGES] pages) and then drops the rows
 *   the server no longer lists (deleted there). The first run is one.
 * - A **later run** first asks for the newest session alone: when it is not newer than the last run's newest and the
 *   server's total is unchanged, nothing changed. Otherwise it pages ([LATER_PAGE] per page) until a page reaches
 *   sessions updated before that newest minus [OVERLAP_MS], or the last page.
 * - The server keeps the `updatedAt` a device uploads (verified on ABS 2.37.1), so a session another device uploads days
 *   late sorts below the overlap. Then the cached count no longer matches the server's total (less the rows it lists
 *   but Lauschig cannot read), and a full run follows – at most once per [FULL_INTERVAL_MS], which also bounds the cost
 *   of a server whose count never matches.
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
        if (since == null) {
            fullRun(api, serverId)
        } else {
            val probe = api.listeningSessions(itemsPerPage = 1, page = 0)
            val top = probe.sessions.firstOrNull()?.updatedAt ?: 0L
            if (top > since || probe.total.toLong() != stamps.get(totalKey(serverId))) laterRun(api, serverId, since)
            if (!inStep(serverId) && fullDue(serverId)) fullRun(api, serverId)
        }
        stamps.set(runKey(serverId), clock())
    }

    /** The newest pages, down to the overlap below the last run's newest session. */
    private suspend fun laterRun(api: MeApi, serverId: String, since: Long) {
        val cutoff = since - OVERLAP_MS
        var newest = since
        var total: Int? = null
        var page = 0
        while (page < MAX_PAGES) {
            val result = api.listeningSessions(itemsPerPage = LATER_PAGE, page = page)
            if (total == null) total = result.total
            val sessions = result.sessions
            newest = maxOf(newest, write(serverId, sessions))
            page++
            if (sessions.isEmpty() || page >= result.numPages || sessions.minOf { it.updatedAt } < cutoff) break
        }
        stamps.set(newestKey(serverId), newest)
        stamps.set(totalKey(serverId), (total ?: 0).toLong())
    }

    /** Every page, then the rows the server no longer lists go. */
    private suspend fun fullRun(api: MeApi, serverId: String) {
        val seen = HashSet<String>()
        var newest = 0L
        var total = 0
        var complete = false
        var page = 0
        while (page < MAX_PAGES) {
            val result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = page)
            total = result.total
            newest = maxOf(newest, write(serverId, result.sessions))
            result.sessions.mapTo(seen) { it.id }
            page++
            if (result.sessions.isEmpty() || page >= result.numPages) {
                complete = true
                break
            }
        }
        if (complete) {
            var stale = dao.ids(serverId).filterNot { it in seen }
            if (stale.isNotEmpty()) {
                // A session updated while the pages were read moved to the top, onto a page read already: read that
                // again, so it is not taken for deleted.
                val head = api.listeningSessions(itemsPerPage = LATER_PAGE, page = 0)
                newest = maxOf(newest, write(serverId, head.sessions))
                head.sessions.mapTo(seen) { it.id }
                total = head.total
                stale = stale.filterNot { it in seen }
                stale.chunked(ListeningSessionDao.DELETE_CHUNK).forEach { dao.delete(serverId, it) }
            }
        }
        val rows = dao.count(serverId)
        stamps.set(newestKey(serverId), newest)
        stamps.set(totalKey(serverId), total.toLong())
        // What the server lists but cannot be read (or lies beyond the cap): not a reason for the next full run.
        stamps.set(unreadableKey(serverId), (total - rows).coerceAtLeast(0).toLong())
        stamps.set(fullKey(serverId), clock())
    }

    /** Writes one page; returns its newest `updatedAt` (0 for none). */
    private suspend fun write(serverId: String, sessions: List<UserListeningSessionDto>): Long {
        if (sessions.isNotEmpty()) dao.upsertAll(sessions.map { it.toEntity(serverId) })
        return sessions.maxOfOrNull { it.updatedAt } ?: 0L
    }

    /** The cache holds as many sessions as the server lists (less the unreadable ones of the last full run). */
    private suspend fun inStep(serverId: String): Boolean {
        val total = stamps.get(totalKey(serverId)) ?: return false
        return dao.count(serverId).toLong() == total - (stamps.get(unreadableKey(serverId)) ?: 0L)
    }

    private suspend fun fullDue(serverId: String): Boolean {
        val last = stamps.get(fullKey(serverId)) ?: return true
        return clock() - last !in 0 until FULL_INTERVAL_MS
    }

    companion object {
        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val OVERLAP_MS = 24 * 60 * 60 * 1000L
        const val FULL_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val FULL_PAGE = 100
        const val LATER_PAGE = 20

        /** Safety cap for one run: 50,000 sessions on a full run. */
        const val MAX_PAGES = 500

        // Every key ends with ":<serverId>", so sign-out forgets them with the account (SyncStamps.removeServer).
        private fun newestKey(serverId: String) = "listening_sessions:$serverId"
        private fun totalKey(serverId: String) = "listening_sessions_total:$serverId"
        private fun unreadableKey(serverId: String) = "listening_sessions_unreadable:$serverId"
        private fun fullKey(serverId: String) = "listening_sessions_full:$serverId"
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
