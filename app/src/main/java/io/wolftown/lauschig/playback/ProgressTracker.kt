package io.wolftown.lauschig.playback

import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Persists listening progress (always dirty, for later sync) and the listening session.
 * Time comes from [clock] (epoch ms) and ticks run on [scope], so tests drive it with virtual time.
 * Positions are global (book-wide) seconds supplied by the caller. Listening time is real elapsed time
 * while playing, regardless of playback speed.
 */
class ProgressTracker(
    private val progressDao: ProgressDao,
    private val sessionDao: SessionDao,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val tickMs: Long = 10_000,
    private val sessionGapMs: Long = 10 * 60_000,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class Target(val serverId: String, val itemId: String, val durationSec: Double, val position: () -> Double)

    private val lock = Mutex()
    private var target: Target? = null
    private var session: LocalSessionEntity? = null
    private var playingSince: Long? = null
    private var pausedAt: Long? = null
    private var tickJob: Job? = null
    private var lastPosition = 0.0

    /** Playback started or resumed. A different title ends the previous one; a pause > 10 min starts a new session. */
    suspend fun onPlaying(serverId: String, itemId: String, durationSec: Double, position: () -> Double) = lock.withLock {
        val cur = target
        if (cur != null && playingSince != null) {
            if (cur.serverId == serverId && cur.itemId == itemId) { target = Target(serverId, itemId, durationSec, position); return@withLock }
            halt(ended = false)
        }
        val now = clock()
        val pos = position()
        val prev = session
        val reuse = prev != null && prev.serverId == serverId && prev.itemId == itemId &&
            pausedAt?.let { now - it <= sessionGapMs } == true
        if (!reuse) {
            val s = LocalSessionEntity(newId(), serverId, itemId, pos, pos, 0.0, now, now)
            session = s
            sessionDao.upsert(s)
        }
        target = Target(serverId, itemId, durationSec, position)
        lastPosition = pos
        playingSince = now
        pausedAt = null
        tickJob = scope.launch {
            while (isActive) {
                delay(tickMs)
                lock.withLock { if (playingSince != null) save(ended = false) }
            }
        }
    }

    /**
     * Pause, stop or service end: stops ticking and saves once. Idempotent: when already paused nothing is saved,
     * so a newer position merged by sync meanwhile is never overwritten (a seek while paused uses [checkpoint]).
     */
    suspend fun onPaused(finalPositionSec: Double? = null) = lock.withLock { halt(ended = false, finalPositionSec) }

    /** Like [onPaused], but only if [itemId] is still the tracked title (its queue may already be gone). */
    suspend fun onPausedFor(serverId: String, itemId: String, finalPositionSec: Double) = lock.withLock {
        val t = target
        if (t != null && t.serverId == serverId && t.itemId == itemId) halt(ended = false, finalPositionSec)
    }

    /** The last file finished: progress is saved as finished at the end of the book. */
    suspend fun onEnded() = lock.withLock { halt(ended = true) }

    /** Saves the position without touching listening time (e.g. a seek while paused). No-op without a known item. */
    suspend fun checkpoint() = lock.withLock { if (target != null) save(ended = false) }

    private suspend fun halt(ended: Boolean, finalPositionSec: Double? = null) {
        tickJob?.cancel()
        tickJob = null
        if (target == null || (playingSince == null && !ended)) return
        save(ended, finalPositionSec)
        playingSince = null
        pausedAt = clock()
    }

    private suspend fun save(ended: Boolean, overridePositionSec: Double? = null) {
        val t = target ?: return
        val now = clock()
        var listening = session?.timeListeningSec ?: 0.0
        playingSince?.let { listening += (now - it) / 1000.0; playingSince = now }
        // The player may already be released when the service is torn down; fall back to the last known position.
        val pos = if (ended) t.durationSec else overridePositionSec ?: try { t.position() } catch (e: IllegalStateException) { lastPosition }
        lastPosition = pos
        val finished = ended || (t.durationSec > 0 && pos / t.durationSec >= FINISHED_FRACTION)
        progressDao.saveLocal(t.serverId, t.itemId, pos, t.durationSec, finished, now)
        session = session?.copy(currentTimeSec = pos, timeListeningSec = listening, updatedAt = now)?.also { sessionDao.upsert(it) }
    }

    companion object {
        const val FINISHED_FRACTION = 0.995
    }
}
