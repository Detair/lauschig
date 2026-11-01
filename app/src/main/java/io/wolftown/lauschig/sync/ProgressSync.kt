package io.wolftown.lauschig.sync

import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.LocalSessionDto
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Reconciles listening state: (1) upload offline sessions, (2) push dirty progress that is newer than the
 * server's, (3) pull newer server progress. Any failure propagates; nothing local is cleared before the
 * server accepted it, so dirty flags survive a failed run.
 */
class ProgressSync(
    private val api: Flow<MeApi?>,
    private val progress: ProgressDao,
    private val sessions: SessionDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val deviceInfo: () -> DeviceInfoDto,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    @Inject constructor(
        apiProvider: ApiProvider,
        progress: ProgressDao,
        sessions: SessionDao,
        items: ItemDao,
        episodes: EpisodeDao,
        device: DeviceInfoProvider,
    ) : this(apiProvider.meApi, progress, sessions, items, episodes, device::get)

    suspend fun sync(serverId: String): List<PositionJump> {
        val api = api.first() ?: return emptyList()
        uploadSessions(api, serverId)

        // Fetched after the session upload: the server may have advanced progress from the sessions.
        // Keyed like local rows: a book by its item id, an episode by its playable key.
        val server = api.me().mediaProgress
            .filter { it.libraryItemId != null }
            .associateBy { it.episodeId?.let { ep -> PlayableKey.episode(it.libraryItemId!!, ep) } ?: it.libraryItemId!! }

        for (local in progress.dirty(serverId)) push(api, serverId, local, server[local.itemId]?.lastUpdate)

        val rows = server.map { (key, it) ->
            ProgressEntity(serverId, key, it.currentTime, it.duration, it.isFinished, it.lastUpdate, hideFromContinue = it.hideFromContinueListening)
        }
        return progress.mergeFromServer(rows)
            .filter { abs(it.newCurrentTimeSec - it.oldCurrentTimeSec) > JUMP_THRESHOLD_SEC }
            .map { PositionJump(it.itemId, it.oldCurrentTimeSec, it.newCurrentTimeSec) }
    }

    private suspend fun uploadSessions(api: MeApi, serverId: String) {
        val pending = sessions.unsynced(serverId)
        if (pending.isEmpty()) return
        val device = deviceInfo()
        val result = api.syncLocalSessions(
            LocalSessionsBody(pending.map { toDto(it, device) }, deviceInfo = device),
        )
        val ok = result.results.filter { it.success }.map { it.id }.toSet()
        // No per-id info at all: HTTP success counts for everything.
        val done = if (result.results.isEmpty()) pending.map { it.id } else pending.map { it.id }.filter { it in ok }
        done.forEach { sessions.markSynced(it) }
    }

    private suspend fun push(api: MeApi, serverId: String, local: ProgressEntity, serverLastUpdate: Long?) {
        if (serverLastUpdate != null && local.lastUpdate <= serverLastUpdate) {
            // Server is newer (merge below replaces us) or identical (nothing to send).
            if (local.lastUpdate == serverLastUpdate) progress.markSynced(serverId, local.itemId, local.lastUpdate)
            return
        }
        val dur = local.durationSec
        val update = ProgressUpdate(
            currentTime = local.currentTimeSec,
            duration = dur,
            progress = if (dur > 0) (local.currentTimeSec / dur).coerceIn(0.0, 1.0) else 0.0,
            isFinished = local.isFinished,
            hideFromContinueListening = local.hideFromContinue,
        )
        val key = PlayableKey.parse(local.itemId)
        try {
            if (key.episodeId != null) api.updateEpisodeProgress(key.libraryItemId, key.episodeId, update)
            else api.updateProgress(key.libraryItemId, update)
        } catch (e: HttpException) {
            if (e.code() == 404) return // item is gone on the server; keep the row dirty, do not block the rest
            throw e
        }
        progress.markSynced(serverId, local.itemId, local.lastUpdate)
    }

    private suspend fun toDto(s: LocalSessionEntity, device: DeviceInfoDto): LocalSessionDto {
        val key = PlayableKey.parse(s.itemId)
        val item = items.get(s.serverId, key.libraryItemId)
        val episode = key.episodeId?.let { episodes.get(s.serverId, key.libraryItemId, it) }
        val day = Instant.ofEpochMilli(s.startedAt).atZone(zone)
        return LocalSessionDto(
            id = s.id,
            libraryItemId = key.libraryItemId,
            episodeId = key.episodeId,
            mediaType = if (key.episodeId != null) "podcast" else "book",
            displayTitle = episode?.title ?: item?.title ?: key.libraryItemId,
            duration = episode?.durationSec ?: item?.durationSec ?: 0.0,
            startTime = s.startTimeSec,
            currentTime = s.currentTimeSec,
            timeListening = s.timeListeningSec,
            startedAt = s.startedAt,
            updatedAt = s.updatedAt,
            deviceInfo = device,
            displayAuthor = item?.author,
            date = day.toLocalDate().toString(),
            dayOfWeek = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
        )
    }

    companion object {
        const val JUMP_THRESHOLD_SEC = 60.0
    }
}
