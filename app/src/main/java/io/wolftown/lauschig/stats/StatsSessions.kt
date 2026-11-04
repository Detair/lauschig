package io.wolftown.lauschig.stats

import io.wolftown.lauschig.db.dao.ListeningSessionDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** The account's sessions for the statistics, from Room only (offline-first): the server's cache and this device's own. */
class StatsSessions @Inject constructor(private val server: ListeningSessionDao, private val local: SessionDao) {
    /** Merged by [ListeningStats.merge], with the names the sessions carry. */
    fun observe(serverId: String): Flow<List<StatSession>> =
        combine(server.observeAll(serverId), local.observeAll(serverId)) { s, l -> ListeningStats.merge(s, l) }

    /**
     * Merged and named from [catalog] ([items] picks its library item id → item map); each emission carries the catalog
     * value it was named from, so the caller needs no second subscription to it.
     */
    fun <T> observeNamed(serverId: String, catalog: Flow<T>, items: (T) -> Map<String, ItemEntity>): Flow<Pair<List<StatSession>, T>> =
        combine(server.observeAll(serverId), local.observeAll(serverId), catalog) { s, l, c -> ListeningStats.merge(s, l, items(c)) to c }
}
