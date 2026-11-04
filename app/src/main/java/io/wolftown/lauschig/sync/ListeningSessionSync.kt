package io.wolftown.lauschig.sync

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.BuildConfig
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** What the statistics page says about the cached history while it is not complete. */
enum class HistoryLoad {
    NONE,

    /** The history is not complete yet and a full run is reading it now. */
    LOADING,

    /**
     * The history is not complete (never read in full, or a full run stopped when the network turned metered): it loads
     * on an unmetered network, or now on the user's tap ([ListeningSessionSync.loadNow]).
     */
    INCOMPLETE,

    /** The history is not complete because the last full read failed; the user may try again ([ListeningSessionSync.loadNow]). */
    FAILED,
}

/**
 * Caches every listening session of the account (`GET /api/me/listening-sessions`, newest `updatedAt` first) in Room,
 * the source of the listening statistics.
 *
 * - A **full run** reads every page ([FULL_PAGE] per page; ABS loads all of the user's sessions for each request, so
 *   few large pages) and then drops the rows the server no longer lists. It runs only on an **unmetered** network
 *   ([unmetered], which looks through a VPN to the Wi-Fi under it), checked again before every page, unless the user
 *   asked for it (`allowMetered`): the first sync, a cache that is empty although the server lists readable sessions,
 *   an interrupted full run, and the reconcile below. Its place is stored after every page, so a cancelled or stopped
 *   run continues there with one page of overlap – if the cache holds what the earlier pages imply, and the server's
 *   total moved by at most a page meanwhile; otherwise it starts over.
 * - While the history is **incomplete** and the network is metered, a run reads the newest page only (recent numbers
 *   stay fresh) and counts as done for the 30-minute throttle. [historyLoad] follows the stored state, not the last run.
 * - A **later run** (any network) first asks for the newest session alone: when it is not newer than the last run's
 *   newest and the server's total is unchanged, nothing changed. Otherwise it pages ([LATER_PAGE] per page) until a
 *   page reaches sessions updated before that newest minus [OVERLAP_MS], or the last page. A page that is not sorted
 *   newest first (the order is ABS's, not a contract; only pages a later run reads are checked) makes it a full run
 *   instead of stopping early – at most once per [FULL_INTERVAL_MS].
 * - **Reconcile**: ABS keeps the `updatedAt` a device uploads, so sessions another device uploads days late sort below
 *   the overlap. When the server lists more sessions than this device can know – the cached ones plus this device's
 *   own uploaded sessions (counted by the statistics from `local_sessions`), less the rows the last full run could not
 *   read – a full run follows, at most once per [FULL_INTERVAL_MS]. Rows the app could not read are tried again after
 *   an app update (a new version may read them), not on a timer.
 * - **Deletions** on the server start no run. They leave the cache only with a full run that happens anyway and reads
 *   the whole, unchanged list in one go (a resumed run, one with unreadable rows or one during which the total changed
 *   drops nothing) – in a steady state that can be a long time, so a session an admin deleted may keep counting.
 *
 * Each page is written as it arrives (one page in memory). The stamps (newest, total) move only after a run read all
 * it had to; a capped run ([maxPages]) moves none – a capped full run continues from its stored place. Offline, and for
 * the device library, nothing happens. Runs of one process are serialized (worker and screen may ask at the same time).
 * Every stamp key ends with `:<serverId>`, so sign-out forgets them with the account.
 */
@Singleton
class ListeningSessionSync(
    private val api: Flow<MeApi?>,
    private val dao: ListeningSessionDao,
    private val localSessions: SessionDao,
    private val stamps: SyncStamps,
    /** True on an unmetered network (Wi-Fi, Ethernet – also under a VPN); full runs wait for one. Asked before every page. */
    private val unmetered: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxPages: Int = MAX_PAGES,
    /** Rows that could not be read are tried again once this changes (an app update). */
    private val appVersion: Long = BuildConfig.VERSION_CODE.toLong(),
    /** Where [loadNow] runs: the app's lifetime, not a screen's. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Inject constructor(
        apiProvider: ApiProvider, dao: ListeningSessionDao, localSessions: SessionDao, stamps: SyncStamps,
        @ApplicationContext context: Context,
    ) : this(apiProvider.meApi, dao, localSessions, stamps, unmetered = { UnmeteredNetwork.isUnmetered(context) })

    private val lock = Mutex()
    /** Full runs reading now, or asked for by [loadNow] and waiting for the connection or the lock. */
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val incomplete = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** Servers whose last full read failed (not: stopped by a metered network). */
    private val failed = MutableStateFlow<Set<String>>(emptySet())
    private val loadJobs = ConcurrentHashMap<String, Job>()

    /**
     * Whether [serverId]'s history is incomplete, and whether a full run is reading it now. Read from the stored state
     * whenever a screen starts to listen (a new account starts from its own stamps, not from what was in memory).
     */
    fun historyLoad(serverId: String): Flow<HistoryLoad> = flow {
        val stored = isIncomplete(serverId)
        incomplete.update { it + (serverId to stored) }
        emitAll(
            combine(incomplete.map { it[serverId] == true }, running.map { serverId in it }, failed.map { serverId in it }) { inc, run, fail ->
                when {
                    !inc -> HistoryLoad.NONE
                    run -> HistoryLoad.LOADING
                    fail -> HistoryLoad.FAILED
                    else -> HistoryLoad.INCOMPLETE
                }
            },
        )
    }.distinctUntilChanged()

    /**
     * „Jetzt laden“: reads the whole history once, over any network (the tap is the consent for this one run). Runs in
     * the app's lifetime – leaving the statistics page does not stop it – and waits for the connection if there is none;
     * a second tap while it waits or reads does nothing. [cancelLoadNow] (sign-out) ends it. Returns its job (tests).
     */
    fun loadNow(serverId: String): Job? {
        if (DeviceLibrary.isLocal(serverId)) return null
        loadJobs[serverId]?.takeIf { it.isActive }?.let { return it }
        running.update { it + serverId }
        val job = scope.launch {
            try {
                api.first { it != null }
                sync(serverId, allowMetered = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listening history not loaded", e)
            } finally {
                running.update { it - serverId }
            }
        }
        loadJobs[serverId] = job
        return job
    }

    /** Sign-out: ends a [loadNow] run of [serverId] and forgets what is kept in memory about its history. */
    fun cancelLoadNow(serverId: String) {
        loadJobs.remove(serverId)?.cancel()
        incomplete.update { it - serverId }
        failed.update { it - serverId }
    }

    /** Background variant: at most once per [MIN_INTERVAL_MS] after a run that did all it could. */
    suspend fun syncIfDue(serverId: String) {
        val last = stamps.get(runKey(serverId))
        if (last != null && clock() - last in 0 until MIN_INTERVAL_MS) return
        sync(serverId)
    }

    /**
     * Fetches now (the statistics page asks on open); a failure throws and keeps the cache. [allowMetered]: the user
     * asked to load the history now – a full run this time reads on any network.
     */
    suspend fun sync(serverId: String, allowMetered: Boolean = false): Unit = lock.withLock {
        if (DeviceLibrary.isLocal(serverId)) return
        val api = api.first() ?: return
        val open = { allowMetered || unmetered() }
        val wasIncomplete = isIncomplete(serverId)
        incomplete.update { it + (serverId to wasIncomplete) }
        if (wasIncomplete) {
            when (if (open()) fullRun(api, serverId, open) else FullRun.STOPPED) {
                FullRun.DONE -> Unit
                FullRun.CAPPED -> return // continues at once next time
                // Metered: the newest sessions now (if any are new), the rest on Wi-Fi; done for the throttle.
                FullRun.STOPPED -> newestOnly(api, serverId)
            }
        } else {
            val newest = stamps.get(newestKey(serverId))!!
            val probe = api.listeningSessions(itemsPerPage = 1, page = 0)
            val top = probe.sessions.firstOrNull()?.updatedAt ?: 0L
            var full = false
            if (top > newest || probe.total.toLong() != (stamps.get(totalKey(serverId)) ?: 0L)) {
                when (laterRun(api, serverId, newest)) {
                    LaterRun.DONE -> Unit
                    LaterRun.UNSORTED -> full = due(serverId, FULL_INTERVAL_MS)
                    LaterRun.CAPPED -> return
                }
            }
            if (!full) full = (unknownOnServer(serverId) || unreadableRetryDue(serverId)) && due(serverId, FULL_INTERVAL_MS)
            // A reconcile on a metered network waits for Wi-Fi quietly; the run still counts for the throttle.
            if (full && open() && fullRun(api, serverId, open) == FullRun.CAPPED) return
        }
        stamps.set(runKey(serverId), clock())
    }

    /**
     * While the history is incomplete on a metered network: one session first (as a later run asks), the newest page
     * only when something is newer than the cache. When that shows the cache holds the whole list – the server lists
     * none, the newest page is all of it, or no more than the cache holds – the history is complete without a full run.
     */
    private suspend fun newestOnly(api: MeApi, serverId: String) {
        val probe = api.listeningSessions(itemsPerPage = 1, page = 0)
        val top = probe.sessions.firstOrNull()?.updatedAt ?: 0L
        var total = probe.total.toLong()
        var covered = total == 0L
        if (!covered && top > (dao.newestUpdatedAt(serverId) ?: 0L)) {
            val page = api.listeningSessions(itemsPerPage = LATER_PAGE, page = 0)
            write(serverId, page.sessions)
            total = page.total.toLong()
            covered = page.numPages <= 1 && page.sessions.size.toLong() == total
        }
        if (!covered) covered = total <= dao.count(serverId) && stamps.get(resumePageKey(serverId))?.let { it == 0L } != false
        if (!covered) return
        stamps.set(newestKey(serverId), dao.newestUpdatedAt(serverId) ?: 0L)
        stamps.set(totalKey(serverId), total)
        stamps.set(unreadableKey(serverId), 0L)
        stamps.set(unreadableAppKey(serverId), appVersion)
        stamps.set(fullKey(serverId), clock())
        stamps.set(resumePageKey(serverId), 0L)
        stamps.set(resumeUnreadableKey(serverId), 0L)
        incomplete.update { it + (serverId to false) }
    }

    /** Never read in full, interrupted, or an empty cache although the server lists sessions Lauschig can read. */
    private suspend fun isIncomplete(serverId: String): Boolean {
        if (stamps.get(newestKey(serverId)) == null) return true
        if ((stamps.get(resumePageKey(serverId)) ?: 0L) > 0L) return true
        val total = stamps.get(totalKey(serverId)) ?: 0L
        return total > (stamps.get(unreadableKey(serverId)) ?: 0L) && dao.count(serverId) == 0
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

    private enum class FullRun { DONE, CAPPED, STOPPED }

    /**
     * Every page from the stored place (or the start), then the rows the server no longer lists go. [open] is asked
     * before every page: a network that turned metered stops the run at its stored place, without stamps.
     */
    private suspend fun fullRun(api: MeApi, serverId: String, open: () -> Boolean): FullRun {
        running.update { it + serverId }
        failed.update { it - serverId }
        try {
            val storedPage = (stamps.get(resumePageKey(serverId)) ?: 0L).toInt()
            val storedTotal = stamps.get(resumeTotalKey(serverId)) ?: 0L
            val storedUnreadable = stamps.get(resumeUnreadableKey(serverId)) ?: 0L
            // One page of overlap: rows that moved down by up to a page since are read again, none is missed. The place
            // is trusted only while the cache holds what the pages before it imply (rows deleted at sign-out, a reset).
            var page = (storedPage - 1).coerceAtLeast(0)
            if (page > 0 && dao.count(serverId) < page.toLong() * FULL_PAGE - storedUnreadable) page = 0
            if (!open()) return FullRun.STOPPED
            var result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = page)
            if (page > 0 && abs(result.total - storedTotal) > FULL_PAGE) {
                // The list moved by more than the overlap covers: start over.
                page = 0
                if (!open()) return FullRun.STOPPED
                result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = 0)
            }
            val resumed = page > 0
            val runTotal = if (resumed) storedTotal else result.total.toLong()
            // Unreadable rows of the whole run, the part before an interruption included.
            var unreadable = if (resumed) storedUnreadable else 0L
            stamps.set(resumeTotalKey(serverId), runTotal)
            val seen = HashSet<String>()
            var newest = 0L
            var moved = false
            var read = 0
            while (true) {
                if (result.total.toLong() != runTotal) moved = true
                val sessions = result.sessions
                val listed = (result.total - page * FULL_PAGE).coerceIn(0, FULL_PAGE)
                val unreadableHere = (listed - sessions.size).coerceAtLeast(0).toLong()
                // Stored without this page's: a resume reads it again (the overlap) and counts it then, once.
                val unreadableBefore = unreadable
                unreadable += unreadableHere
                newest = maxOf(newest, write(serverId, sessions))
                sessions.mapTo(seen) { it.id }
                page++
                read++
                stamps.set(resumePageKey(serverId), page.toLong())
                stamps.set(resumeUnreadableKey(serverId), unreadableBefore)
                if (page >= result.numPages) break
                if (read >= maxPages) return FullRun.CAPPED // the stored place continues it next time
                if (!open()) return FullRun.STOPPED
                result = api.listeningSessions(itemsPerPage = FULL_PAGE, page = page)
            }
            // Drop rows only after a run that saw the whole, unchanged list (a skipped or shifted row is not a deletion).
            if (!resumed && unreadable == 0L && !moved) prune(api, serverId, seen)
            stamps.set(newestKey(serverId), maxOf(newest, dao.newestUpdatedAt(serverId) ?: 0L))
            stamps.set(totalKey(serverId), result.total.toLong())
            stamps.set(unreadableKey(serverId), unreadable)
            stamps.set(unreadableAppKey(serverId), appVersion)
            stamps.set(fullKey(serverId), clock())
            stamps.set(resumePageKey(serverId), 0L)
            stamps.set(resumeUnreadableKey(serverId), 0L)
            incomplete.update { it + (serverId to false) }
            return FullRun.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.update { it + serverId }
            throw e
        } finally {
            if (loadJobs[serverId]?.isActive != true) running.update { it - serverId }
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

    /** Rows the last full run could not read, and the app was updated since (it may read them now). */
    private suspend fun unreadableRetryDue(serverId: String): Boolean =
        (stamps.get(unreadableKey(serverId)) ?: 0L) > 0 && stamps.get(unreadableAppKey(serverId)) != appVersion

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
        const val FULL_PAGE = 250
        const val LATER_PAGE = 20

        /** Pages one run reads at most: 125,000 sessions in a full run (it continues next time), 10,000 in a later one. */
        const val MAX_PAGES = 500

        // Every key ends with ":<serverId>", so sign-out forgets them with the account (SyncStamps.removeServer).
        internal fun keys(serverId: String) = listOf(
            newestKey(serverId), totalKey(serverId), unreadableKey(serverId), unreadableAppKey(serverId), fullKey(serverId),
            runKey(serverId), resumePageKey(serverId), resumeTotalKey(serverId), resumeUnreadableKey(serverId),
        )
        private fun newestKey(serverId: String) = "listening_sessions:$serverId"
        private fun totalKey(serverId: String) = "listening_sessions_total:$serverId"
        private fun unreadableKey(serverId: String) = "listening_sessions_unreadable:$serverId"
        private fun unreadableAppKey(serverId: String) = "listening_sessions_unreadable_app:$serverId"
        private fun fullKey(serverId: String) = "listening_sessions_full:$serverId"
        private fun runKey(serverId: String) = "listening_sessions_run:$serverId"
        private fun resumePageKey(serverId: String) = "listening_sessions_resume_page:$serverId"
        private fun resumeTotalKey(serverId: String) = "listening_sessions_resume_total:$serverId"
        private fun resumeUnreadableKey(serverId: String) = "listening_sessions_resume_unreadable:$serverId"
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
