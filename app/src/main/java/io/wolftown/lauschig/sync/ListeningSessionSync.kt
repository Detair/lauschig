package io.wolftown.lauschig.sync

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.UserListeningSessionDto
import io.wolftown.lauschig.db.dao.ListeningSessionDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** What the statistics page says about the cached history while it is not complete. */
enum class HistoryLoad {
    NONE,

    /** A full run is reading the whole history now. */
    LOADING,

    /** The history is not complete, and the full run waits for an unmetered network. */
    WAITING_FOR_UNMETERED,
}

/**
 * Caches every listening session of the account (`GET /api/me/listening-sessions`, newest `updatedAt` first) in Room,
 * the source of the listening statistics.
 *
 * - A **full run** reads every page ([FULL_PAGE] per page; ABS loads all of the user's sessions for each request, so
 *   few large pages) and then drops the rows the server no longer lists (deleted there). It runs only on an
 *   **unmetered** network: the first sync, a cache that is empty (whatever the stamps say), an interrupted full run, and
 *   the reconcile below. Its place is stored after every page, so a cancelled run continues there with one page of
 *   overlap – or starts over when the server's total moved by more than a page meanwhile.
 * - A **later run** (any network) first asks for the newest session alone: when it is not newer than the last run's
 *   newest and the server's total is unchanged, nothing changed. Otherwise it pages ([LATER_PAGE] per page) until a
 *   page reaches sessions updated before that newest minus [OVERLAP_MS], or the last page. A page that is not sorted
 *   newest first (the order is ABS's, not a contract) makes it a full run instead of stopping early.
 * - **Reconcile**: ABS keeps the `updatedAt` a device uploads, so sessions another device uploads days late sort below
 *   the overlap. When the server lists more sessions than this device can know – the cached ones plus this device's
 *   own uploaded sessions (counted by the statistics from `local_sessions`), less the rows the last full run could not
 *   read – a full run follows, at most once per [FULL_INTERVAL_MS]. Fewer on the server (deletions) start none; the
 *   next full run drops them. Unreadable rows are retried by a full run after [UNREADABLE_RETRY_MS].
 *
 * Each page is written as it arrives (one page in memory). The stamps (newest, total) move only after a run read all
 * it had to; a capped run ([maxPages]) moves none – a capped full run continues from its stored place. A full run drops
 * rows only when it read the whole list in one go, every listed row was readable and the total did not change while it
 * read. Offline, and for the device library, nothing happens. Runs of one process are serialized (worker and screen
 * may ask at the same time). Every stamp key ends with `:<serverId>`, so sign-out forgets them with the account.
 */
@Singleton
class ListeningSessionSync(
    private val api: Flow<MeApi?>,
    private val dao: ListeningSessionDao,
    private val localSessions: SessionDao,
    private val stamps: SyncStamps,
    /** True on an unmetered network (Wi-Fi, Ethernet); full runs wait for one. */
    private val unmetered: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPages: Int = MAX_PAGES,
) {
    @Inject constructor(
        apiProvider: ApiProvider, dao: ListeningSessionDao, localSessions: SessionDao, stamps: SyncStamps,
        @ApplicationContext context: Context,
    ) : this(
        apiProvider.meApi, dao, localSessions, stamps,
        unmetered = { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false },
    )

    private val lock = Mutex()
    private val loads = MutableStateFlow<Map<String, HistoryLoad>>(emptyMap())

    /** Whether [serverId]'s history is being read in full, or waits for an unmetered network to be. */
    fun historyLoad(serverId: String): Flow<HistoryLoad> = loads.map { it[serverId] ?: HistoryLoad.NONE }.distinctUntilChanged()

    private fun setLoad(serverId: String, load: HistoryLoad) = loads.update { it + (serverId to load) }

    /** Background variant: at most once per [MIN_INTERVAL_MS] after a run that did all it had to. */
    suspend fun syncIfDue(serverId: String) {
        val last = stamps.get(runKey(serverId))
        if (last != null && clock() - last in 0 until MIN_INTERVAL_MS) return
        sync(serverId)
    }

    /** Fetches now (the statistics page asks on open); a failure throws and keeps the cache. */
    suspend fun sync(serverId: String): Unit = lock.withLock {
        if (DeviceLibrary.isLocal(serverId)) return
        val api = api.first() ?: return
        val newest = stamps.get(newestKey(serverId))
        val storedTotal = stamps.get(totalKey(serverId)) ?: 0L
        // An empty cache is a first sync whatever the stamps say (rows purged without them) – unless the server had none.
        val incomplete = newest == null || (stamps.get(resumePageKey(serverId)) ?: 0L) > 0L ||
            (storedTotal > 0 && dao.count(serverId) == 0)
        var full = incomplete
        if (!incomplete) {
            val probe = api.listeningSessions(itemsPerPage = 1, page = 0)
            val top = probe.sessions.maxOfOrNull { it.updatedAt } ?: 0L
            if (top > newest!! || probe.total.toLong() != storedTotal) {
                when (laterRun(api, serverId, newest)) {
                    LaterRun.DONE -> Unit
                    LaterRun.UNSORTED -> full = true
                    LaterRun.CAPPED -> return
                }
            }
            if (!full) full = (unknownOnServer(serverId) && due(serverId, FULL_INTERVAL_MS)) || unreadableRetryDue(serverId)
        }
        if (full) {
            if (!unmetered()) {
                // No run stamp: the next sync looks again (without a request while the history is incomplete).
                if (incomplete) setLoad(serverId, HistoryLoad.WAITING_FOR_UNMETERED)
                return
            }
            if (!fullRun(api, serverId)) return
        }
        stamps.set(runKey(serverId), clock())
    }

    private enum class LaterRun { DONE, UNSORTED, CAPPED }

    /** The newest pages, down to the overlap below the last run's newest session. */
    private suspend fun laterRun(api: MeApi, serverId: String, since: Long): LaterRun {
        val cutoff = since - OVERLAP_MS
        var newest = since
        var total: Int? = null
        var previousLast: Long? = null
        var page = 0
        while (true) {
            if (page >= maxPages) return LaterRun.CAPPED
            val result = api.listeningSessions(itemsPerPage = LATER_PAGE, page = page)
            if (total == null) total = result.total
            val sessions = result.sessions
            newest = maxOf(newest, write(serverId, sessions))
            if (!newestFirst(sessions, previousLast)) {
                warnUnsorted()
                return LaterRun.UNSORTED
            }
            previousLast = sessions.lastOrNull()?.updatedAt ?: previousLast
            page++
            if (sessions.isEmpty() || page >= result.numPages || sessions.last().updatedAt < cutoff) break
        }
        stamps.set(newestKey(serverId), newest)
        stamps.set(totalKey(serverId), (total ?: 0).toLong())
        return LaterRun.DONE
    }

    /** Every page from the stored place (or the start), then the rows the server no longer lists go. False when capped. */
    private suspend fun fullRun(api: MeApi, serverId: String): Boolean {
        setLoad(serverId, HistoryLoad.LOADING)
        try {
            val storedPage = (stamps.get(resumePageKey(serverId)) ?: 0L).toInt()
            val storedTotal = stamps.get(resumeTotalKey(serverId)) ?: 0L
            // One page of overlap: rows that moved down by up to a page since are read again, none is missed.
            var page = (storedPage - 1).coerceAtLeast(0)
            var result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = page)
            if (page > 0 && abs(result.total - storedTotal) > FULL_PAGE) {
                // The list moved by more than the overlap covers: start over.
                page = 0
                result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = 0)
            }
            val resumed = page > 0
            val runTotal = if (resumed) storedTotal else result.total.toLong()
            stamps.set(resumeTotalKey(serverId), runTotal)
            val seen = HashSet<String>()
            var newest = 0L
            var unreadable = 0
            var moved = false
            var read = 0
            while (true) {
                if (result.total.toLong() != runTotal) moved = true
                val sessions = result.sessions
                val listed = (result.total - page * FULL_PAGE).coerceIn(0, FULL_PAGE)
                unreadable += (listed - sessions.size).coerceAtLeast(0)
                newest = maxOf(newest, write(serverId, sessions))
                sessions.mapTo(seen) { it.id }
                page++
                read++
                stamps.set(resumePageKey(serverId), page.toLong())
                if (page >= result.numPages) break
                if (read >= maxPages) return false // capped: the stored place continues it next time
                result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = page)
            }
            // Drop rows only after a run that saw the whole, unchanged list (a skipped or shifted row is not a deletion).
            if (!resumed && unreadable == 0 && !moved) prune(api, serverId, seen)
            val total = result.total.toLong()
            stamps.set(newestKey(serverId), maxOf(newest, dao.newestUpdatedAt(serverId) ?: 0L))
            stamps.set(totalKey(serverId), total)
            // Listed but not readable by Lauschig: not a reason for a full run every day, but retried after
            // UNREADABLE_RETRY_MS (an app or server update may make them readable).
            stamps.set(unreadableKey(serverId), unreadable.toLong())
            stamps.set(fullKey(serverId), clock())
            stamps.set(resumePageKey(serverId), 0L)
            return true
        } finally {
            setLoad(serverId, HistoryLoad.NONE)
        }
    }

    /** Deletes the cached rows the run did not see, after re-reading the head (a session updated meanwhile moved up). */
    private suspend fun prune(api: MeApi, serverId: String, seen: MutableSet<String>) {
        var stale = dao.ids(serverId).filterNot { it in seen }
        if (stale.isEmpty()) return
        val head = api.listeningSessions(itemsPerPage = LATER_PAGE, page = 0)
        write(serverId, head.sessions)
        head.sessions.mapTo(seen) { it.id }
        stale = stale.filterNot { it in seen }
        stale.chunked(ListeningSessionDao.DELETE_CHUNK).forEach { dao.delete(serverId, it) }
    }

    /** Writes one page; returns its newest `updatedAt` (0 for none). */
    private suspend fun write(serverId: String, sessions: List<UserListeningSessionDto>): Long {
        if (sessions.isNotEmpty()) dao.upsertAll(sessions.map { it.toEntity(serverId) })
        return sessions.maxOfOrNull { it.updatedAt } ?: 0L
    }

    /** Sessions this device knows of: cached from the server, or recorded here and uploaded (same id). */
    private suspend fun knownCount(serverId: String): Long =
        HashSet(dao.ids(serverId)).apply { addAll(localSessions.syncedIds(serverId)) }.size.toLong()

    /** The server lists sessions this device cannot know (another device's late uploads, beyond the overlap). */
    private suspend fun unknownOnServer(serverId: String): Boolean {
        val total = stamps.get(totalKey(serverId)) ?: return false
        if (total <= dao.count(serverId)) return false
        return total > knownCount(serverId) + (stamps.get(unreadableKey(serverId)) ?: 0L)
    }

    private suspend fun unreadableRetryDue(serverId: String): Boolean =
        (stamps.get(unreadableKey(serverId)) ?: 0L) > 0 && due(serverId, UNREADABLE_RETRY_MS)

    /** No full run in the last [interval]. */
    private suspend fun due(serverId: String, interval: Long): Boolean {
        val last = stamps.get(fullKey(serverId)) ?: return true
        return clock() - last !in 0 until interval
    }

    /** Newest `updatedAt` first, within the page and after the previous page's last session. */
    private fun newestFirst(sessions: List<UserListeningSessionDto>, previousLast: Long?): Boolean =
        (previousLast == null || sessions.isEmpty() || sessions.first().updatedAt <= previousLast) &&
            sessions.zipWithNext().all { (a, b) -> a.updatedAt >= b.updatedAt }

    private fun warnUnsorted() {
        if (warnedUnsorted) return
        warnedUnsorted = true
        Log.w(TAG, "listening sessions are not sorted newest updatedAt first; reading all of them instead")
    }

    companion object {
        private const val TAG = "ListeningSessionSync"
        @Volatile private var warnedUnsorted = false

        const val MIN_INTERVAL_MS = 30 * 60 * 1000L
        const val OVERLAP_MS = 24 * 60 * 60 * 1000L
        const val FULL_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val UNREADABLE_RETRY_MS = 7 * 24 * 60 * 60 * 1000L
        const val FULL_PAGE = 250
        const val LATER_PAGE = 20

        /** Pages one run reads at most: 125,000 sessions in a full run (it continues next time), 10,000 in a later one. */
        const val MAX_PAGES = 500

        // Every key ends with ":<serverId>", so sign-out forgets them with the account (SyncStamps.removeServer).
        internal fun keys(serverId: String) = listOf(
            newestKey(serverId), totalKey(serverId), unreadableKey(serverId), fullKey(serverId), runKey(serverId),
            resumePageKey(serverId), resumeTotalKey(serverId),
        )
        private fun newestKey(serverId: String) = "listening_sessions:$serverId"
        private fun totalKey(serverId: String) = "listening_sessions_total:$serverId"
        private fun unreadableKey(serverId: String) = "listening_sessions_unreadable:$serverId"
        private fun fullKey(serverId: String) = "listening_sessions_full:$serverId"
        private fun runKey(serverId: String) = "listening_sessions_run:$serverId"
        private fun resumePageKey(serverId: String) = "listening_sessions_resume_page:$serverId"
        private fun resumeTotalKey(serverId: String) = "listening_sessions_resume_total:$serverId"
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
