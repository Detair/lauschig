package io.wolftown.lauschig.history

import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.db.dao.HistoryDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.HistorySessionEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Listening history per playable: cached server sessions + this device's sessions (works offline). */
class HistoryRepository(
    private val api: Flow<MeApi?>,
    private val history: HistoryDao,
    private val sessions: SessionDao,
) {
    @Inject constructor(apiProvider: ApiProvider, history: HistoryDao, sessions: SessionDao) : this(apiProvider.meApi, history, sessions)

    fun observe(serverId: String, key: String): Flow<History> =
        combine(history.observeForKey(serverId, key), sessions.observeForKey(serverId, key)) { server, local -> HistoryAggregation.build(server, local) }

    /** Replaces the cache with the server's newest [LIMIT] sessions; offline is a no-op, a failure throws and keeps the cache. */
    suspend fun refresh(serverId: String, key: String) {
        if (DeviceLibrary.isLocal(serverId, key)) return // no server knows a device title; its history is this device's
        val api = api.first() ?: return
        val parsed = PlayableKey.parse(key)
        val episodeId = parsed.episodeId
        val page = if (episodeId != null) api.episodeListeningSessions(parsed.libraryItemId, episodeId, LIMIT)
        else api.itemListeningSessions(parsed.libraryItemId, LIMIT)
        history.replaceForKey(
            serverId, key,
            page.sessions.map {
                HistorySessionEntity(
                    serverId, it.id, key, it.startedAt, it.updatedAt, it.startTime, it.currentTime, it.timeListening,
                    it.deviceInfo?.deviceName?.takeIf { n -> n.isNotBlank() } ?: it.deviceInfo?.model ?: it.deviceInfo?.clientName,
                )
            },
        )
    }

    companion object {
        const val LIMIT = 100
    }
}
