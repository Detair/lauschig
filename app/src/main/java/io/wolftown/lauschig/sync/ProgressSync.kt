package io.wolftown.lauschig.sync

import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.LocalSessionDto
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.me.MediaProgressDto
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

/**
 * Reconciles listening state: (1) push dirty progress that is newer than the server's (or as new but different),
 * (2) upload offline sessions, (3) pull newer server progress. Any failure propagates; nothing local is cleared before
 * the server accepted it, so dirty flags survive a failed run. One sync at a time per process.
 *
 * The order matters with ABS (`PlaybackSessionManager.syncLocalSession`): an uploaded session overwrites the server
 * progress with its position and its own `updatedAt` unless the progress is newer, and never carries `isFinished`. The
 * tracker writes progress and session with one timestamp, so uploading sessions first turned a title heard to its end
 * into "not finished" on the server at the same timestamp, and the push then saw nothing newer to send. Pushed first,
 * the progress carries the server's clock and ABS keeps it.
 */
class ProgressSync(
    private val api: Flow<MeApi?>,
    private val progress: ProgressDao,
    private val sessions: SessionDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val deviceInfo: () -> DeviceInfoDto,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** When the last run went through (Verbindungsstatus window). */
    private val stamps: SyncStamps = InMemorySyncStamps(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        apiProvider: ApiProvider,
        progress: ProgressDao,
        sessions: SessionDao,
        items: ItemDao,
        episodes: EpisodeDao,
        device: DeviceInfoProvider,
        stamps: SyncStamps,
    ) : this(apiProvider.meApi, progress, sessions, items, episodes, device::get, stamps = stamps)

    suspend fun sync(serverId: String): List<PositionJump> = lock.withLock {
        // The device library never syncs; a local key under a real server (should not exist) is never sent either.
        if (DeviceLibrary.isLocal(serverId)) return@withLock emptyList()
        val api = api.first() ?: return@withLock emptyList()

        var server = fetchProgress(api)
        var pushed = false
        for (local in progress.dirty(serverId)) {
            if (DeviceLibrary.isLocalKey(local.itemId)) continue
            if (push(api, serverId, local, server[local.itemId])) pushed = true
        }
        val uploaded = uploadSessions(api, serverId)
        // The server may have advanced (or auto-finished) progress from the pushes and sessions: merge its newest state.
        if (pushed || uploaded) server = fetchProgress(api)

        val rows = server.map { (key, it) ->
            ProgressEntity(serverId, key, it.currentTime, it.duration, it.isFinished, it.lastUpdate, hideFromContinue = it.hideFromContinueListening)
        }
        val jumps = progress.mergeFromServer(rows)
            .filter { abs(it.newCurrentTimeSec - it.oldCurrentTimeSec) > JUMP_THRESHOLD_SEC }
            .map { PositionJump(it.itemId, it.oldCurrentTimeSec, it.newCurrentTimeSec) }
        stamps.set(stampKey(serverId), clock())
        jumps
    }

    /** Keyed like local rows: a book by its item id, an episode by its playable key. */
    private suspend fun fetchProgress(api: MeApi): Map<String, MediaProgressDto> =
        api.me().mediaProgress
            .filter { it.libraryItemId != null }
            .associateBy { it.episodeId?.let { ep -> PlayableKey.episode(it.libraryItemId!!, ep) } ?: it.libraryItemId!! }

    /** True if anything was uploaded. */
    private suspend fun uploadSessions(api: MeApi, serverId: String): Boolean {
        val pending = sessions.unsynced(serverId).filterNot { DeviceLibrary.isLocalKey(it.itemId) }
        if (pending.isEmpty()) return false
        val device = deviceInfo()
        val result = api.syncLocalSessions(
            LocalSessionsBody(pending.map { toDto(it, device) }, deviceInfo = device),
        )
        val ok = result.results.filter { it.success }.map { it.id }.toSet()
        // No per-id info at all: HTTP success counts for everything.
        val done = if (result.results.isEmpty()) pending.map { it.id } else pending.map { it.id }.filter { it in ok }
        done.forEach { sessions.markSynced(it) }
        return true
    }

    /**
     * Sends [local] unless the server's row is newer (the merge replaces us) or as new and the same. As new but different
     * (finished or hidden state, or the position) is sent: the local row is the user's explicit state. True if sent.
     */
    private suspend fun push(api: MeApi, serverId: String, local: ProgressEntity, server: MediaProgressDto?): Boolean {
        if (server != null && local.lastUpdate < server.lastUpdate) return false
        if (server != null && local.lastUpdate == server.lastUpdate && sameState(local, server)) {
            progress.markSynced(serverId, local.itemId, local.lastUpdate)
            return false
        }
        val dur = local.durationSec
        val update = ProgressUpdate(
            currentTime = local.currentTimeSec,
            duration = dur,
            // A finished title may keep an earlier position (see ProgressActions.markFinished); ABS must still see 100 %.
            progress = if (local.isFinished) 1.0 else if (dur > 0) (local.currentTimeSec / dur).coerceIn(0.0, 1.0) else 0.0,
            isFinished = local.isFinished,
            hideFromContinueListening = local.hideFromContinue,
        )
        val key = PlayableKey.parse(local.itemId)
        val episodeId = key.episodeId
        try {
            if (episodeId != null) api.updateEpisodeProgress(key.libraryItemId, episodeId, update)
            else api.updateProgress(key.libraryItemId, update)
        } catch (e: HttpException) {
            if (e.code() == 404) return false // item is gone on the server; keep the row dirty, do not block the rest
            throw e
        }
        progress.markSynced(serverId, local.itemId, local.lastUpdate)
        return true
    }

    private fun sameState(local: ProgressEntity, server: MediaProgressDto) =
        local.isFinished == server.isFinished && local.hideFromContinue == server.hideFromContinueListening &&
            abs(local.currentTimeSec - server.currentTime) < SAME_POSITION_SEC

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
        /** [SyncStamps] key of the last successful run for [serverId]. */
        fun stampKey(serverId: String) = "progress_sync:$serverId"
        const val JUMP_THRESHOLD_SEC = 60.0
        private const val SAME_POSITION_SEC = 1.0

        /** Process-wide: the periodic sync and the progress-only sync never push the same rows at once. */
        private val lock = Mutex()
    }
}
