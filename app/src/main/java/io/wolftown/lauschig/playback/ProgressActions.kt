package io.wolftown.lauschig.playback

import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User actions on a title's progress. Each one is a local write marked dirty (newest wins on the next sync,
 * which is triggered right away), so it works offline and reaches the server later.
 */
@Singleton
class ProgressActions(
    private val progress: ProgressDao,
    private val items: ItemDao,
    private val episodes: EpisodeDao,
    private val player: PlayerController,
    private val meApi: Flow<MeApi?>,
    private val sync: SyncTrigger,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Time for the paused player's own save to land before the action overwrites it. */
    private val settleMs: Long = 400,
) {
    @Inject constructor(progress: ProgressDao, items: ItemDao, episodes: EpisodeDao, player: PlayerController, api: ApiProvider, sync: SyncTrigger) :
        this(progress, items, episodes, player, api.meApi, sync)

    suspend fun markFinished(serverId: String, itemId: String) {
        val duration = duration(serverId, itemId)
        stopIfLoaded(itemId)
        write(serverId, itemId) { it.copy(currentTimeSec = duration, durationSec = duration, isFinished = true) }
    }

    /** Not finished, back to the start (ABS resets the position when a title is unmarked anyway). */
    suspend fun startOver(serverId: String, itemId: String) {
        val duration = duration(serverId, itemId)
        val loaded = stopIfLoaded(itemId)
        write(serverId, itemId) { it.copy(currentTimeSec = 0.0, durationSec = duration, isFinished = false) }
        if (loaded) player.seekTo(0.0)
    }

    /** Hidden from "continue listening"; the position stays. Listening again shows it again. */
    suspend fun hideFromContinue(serverId: String, itemId: String) {
        val row = progress.get(serverId, itemId) ?: return
        progress.saveUserAction(row.copy(hideFromContinue = true, lastUpdate = clock()))
        sync.syncNow()
    }

    /**
     * Takes the server's position for this title even if the local one is newer. Returns false when the server
     * is not reachable or has no progress for it. A loaded, paused title jumps there.
     */
    suspend fun pullFromServer(serverId: String, itemId: String): Boolean {
        val api = meApi.first() ?: return false
        val key = PlayableKey.parse(itemId)
        val dto = try {
            api.me().mediaProgress.firstOrNull { it.libraryItemId == key.libraryItemId && it.episodeId == key.episodeId }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } ?: return false
        progress.replaceFromServer(
            ProgressEntity(serverId, itemId, dto.currentTime, dto.duration, dto.isFinished, dto.lastUpdate, hideFromContinue = dto.hideFromContinueListening),
        )
        val s = player.state.value
        if (s.itemId == itemId && !s.isPlaying) player.seekTo(dto.currentTime)
        return true
    }

    private suspend fun duration(serverId: String, itemId: String): Double {
        val saved = progress.get(serverId, itemId)?.durationSec?.takeIf { it > 0 }
        if (saved != null) return saved
        val key = PlayableKey.parse(itemId)
        return if (key.episodeId != null) episodes.get(serverId, key.libraryItemId, key.episodeId)?.durationSec ?: 0.0
        else items.get(serverId, itemId)?.durationSec ?: 0.0
    }

    /** Pauses the title if it is playing, and waits for its own progress save so the action is written last. */
    private suspend fun stopIfLoaded(itemId: String): Boolean {
        val s = player.state.value
        if (s.itemId != itemId) return false
        if (s.isPlaying) {
            player.togglePlayPause()
            withTimeoutOrNull(2_000) { while (player.state.value.isPlaying) delay(20) }
            delay(settleMs)
        }
        return true
    }

    private suspend fun write(serverId: String, itemId: String, change: (ProgressEntity) -> ProgressEntity) {
        val base = progress.get(serverId, itemId) ?: ProgressEntity(serverId, itemId, 0.0, 0.0, false, 0)
        progress.saveUserAction(change(base).copy(lastUpdate = clock(), hideFromContinue = false))
        sync.syncNow()
    }
}
