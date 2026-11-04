package io.wolftown.lauschig.stats

import io.wolftown.lauschig.db.dao.ListeningSessionDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** One account's rows as Room has them: the server's cache and this device's own sessions. */
class SessionRows(val server: List<ListeningSessionEntity>, val local: List<LocalSessionEntity>)

/** The account's sessions for the statistics, from Room only (offline-first): the server's cache and this device's own. */
class StatsSessions @Inject constructor(private val server: ListeningSessionDao, private val local: SessionDao) {
    /** Every row, unmerged: callers conflate a burst of writes first and merge ([ListeningStats.merge]) once. */
    fun observeRows(serverId: String): Flow<SessionRows> =
        combine(server.observeAll(serverId), local.observeAll(serverId), ::SessionRows)

    /** Rows of sessions started at or after [sinceMs] (the start card's last days). */
    fun observeRowsSince(serverId: String, sinceMs: Long): Flow<SessionRows> =
        combine(server.observeSince(serverId, sinceMs), local.observeSince(serverId, sinceMs), ::SessionRows)

    /** Merged sessions started in [fromMs, toMs), read once (a streak that reaches back further). */
    suspend fun between(serverId: String, fromMs: Long, toMs: Long): List<StatSession> =
        ListeningStats.merge(server.between(serverId, fromMs, toMs), local.between(serverId, fromMs, toMs))
}
