package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.FinishedDownload
import io.wolftown.lauschig.db.dao.ItemBytes
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.ProgressTracker
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One complete download of a finished title, as the automatic clean-up judges it. Times on the device's clock. */
data class AutoDeleteCandidate(
    val key: String,
    val positionSec: Double,
    val durationSec: Double,
    /** When the download was complete ([DownloadFiles.completedAt]); null = unknown (then it is never deleted). */
    val completedAt: Long?,
    /** When this device first saw the title finished ([FinishedSeenStore]); null = not seen yet. */
    val finishedSeenAt: Long?,
)

/**
 * Which finished downloads the automatic clean-up deletes ([AutoDeleteMode]):
 * - The title really ended (played to the end) or was marked finished – not the progress tracker's mark in the last
 *   0.5 % (a pause, a sleep timer or a phone call just before the end).
 * - The download was already complete when the title became finished, both on the device's clock: a title downloaded
 *   again to hear it once more – also a paused or failed download resumed after the title was finished – stays until it is
 *   finished again.
 * - Never: a title the user keeps ([AppSettings.cleanupIgnored], "Behalten"), a title the player holds (loaded in any
 *   state – or, while the app's controller knows no title, the one last played on this device –, in the queue, or
 *   waiting for the "Download löschen?" answer), a device title.
 * The delay of the 3- and 7-day modes counts from when the device saw the title finished.
 */
object AutoDeleteRules {
    /** "Ended": the saved position is at most this far from the end (the tracker saves the full length at the end). */
    const val END_SLACK_SEC = 2.0

    fun reallyFinished(positionSec: Double, durationSec: Double): Boolean =
        durationSec <= 0 || positionSec >= durationSec - END_SLACK_SEC || positionSec / durationSec < ProgressTracker.FINISHED_FRACTION

    /** Counts at all (whenever it is due); [keep] says which keys must stay. */
    fun counts(c: AutoDeleteCandidate, keep: (String) -> Boolean): Boolean {
        val completed = c.completedAt ?: return false
        val seen = c.finishedSeenAt?.takeIf { it >= 0 } ?: return false // BEFORE_BASELINE: finished before this device looked
        return completed <= seen && reallyFinished(c.positionSec, c.durationSec) && !keep(c.key)
    }

    fun due(candidates: List<AutoDeleteCandidate>, mode: AutoDeleteMode, now: Long, keep: (String) -> Boolean): List<String> {
        val after = mode.afterMs ?: return emptyList()
        return candidates.filter { counts(it, keep) && now - it.finishedSeenAt!! >= after }.map { it.key }
    }

    /** When the next download that is not due yet will be; null if none is waiting. */
    fun nextDue(candidates: List<AutoDeleteCandidate>, mode: AutoDeleteMode, now: Long, keep: (String) -> Boolean): Long? {
        val after = mode.afterMs?.takeIf { it > 0 } ?: return null
        return candidates.filter { counts(it, keep) }.map { it.finishedSeenAt!! + after }.filter { it > now }.minOrNull()
    }

    /** "serverId:key" entries the user keeps, the [protected] keys (the player's), and every device title. */
    fun keeper(serverId: String, kept: Set<String>, protected: Set<String>): (String) -> Boolean = { key ->
        "$serverId:$key" in kept || key in protected || DeviceLibrary.isLocal(serverId, key)
    }
}

/** What turning the automatic clean-up on would delete (now or once due): for the question before it is switched on. */
data class AutoDeletePreview(val count: Int, val bytes: Long)

/**
 * Deletes finished downloads of the signed-in account by [AutoDeleteRules] through [DownloadManager.cancel] (files and
 * the download row; progress, sessions and bookmarks stay). In the app it watches the finished downloads, the setting,
 * the kept list, the player's title and the queue, and acts right away ("Sofort") or when the next one falls due; a title
 * the player holds is looked at again a minute later (the last played title has no change signal). While the app is not
 * running [FinishedDownloadsWorker] runs it once a day with the same guards. It always notes when titles become finished
 * ([FinishedSeenStore]), also with "Nie", so turning it on later judges re-downloads right.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FinishedDownloadCleaner(
    private val accounts: AccountStore,
    private val downloads: DownloadDao,
    private val finishedKeys: (serverId: String) -> Flow<List<String>>,
    private val settings: DownloadSettings,
    private val app: AppSettings,
    private val manager: DownloadManager,
    private val completedAt: suspend (serverId: String, key: String) -> Long?,
    private val seen: FinishedSeenStore,
    /** The title loaded in the player, playing or not (null: none). */
    private val loadedKey: Flow<String?>,
    private val lastPlayed: suspend () -> LastPlayed?,
    private val queue: (serverId: String) -> Flow<List<String>>,
    private val localBytes: (serverId: String) -> Flow<List<ItemBytes>>,
    /** Titles whose "Download löschen?" question is not settled ([FinishedQuestions.holds]). */
    private val questionHolds: Flow<Set<FinishedQuestion>> = flowOf(emptySet()),
    /** Titles on their way into the player and the service's title ([TitleHandover]). */
    private val handover: Flow<TitleHandover.State> = TitleHandover.state,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recheckMs: Long = 60_000,
    /** How long a title stays held after it left the player's hold. */
    private val graceMs: Long = TitleHandover.GRACE_MS,
    /** How long a pick holds its title ([TitleHandover.pick]). */
    private val pickMs: Long = TitleHandover.GRACE_MS,
) {
    @Inject constructor(
        accounts: AccountStore,
        downloads: DownloadDao,
        progress: ProgressDao,
        settings: DownloadSettings,
        app: AppSettings,
        manager: DownloadManager,
        files: DownloadFiles,
        seen: FinishedSeenStore,
        player: PlayerController,
        lastPlayed: LastPlayedStore,
        queue: QueueDao,
        audioFiles: AudioFileDao,
        questions: FinishedQuestions,
    ) : this(
        accounts, downloads, progress::observeFinishedKeys, settings, app, manager, files::completedAt, seen,
        player.state.map { it.itemId }, lastPlayed::get, { sid -> queue.observe(sid).map { rows -> rows.map { it.itemKey } } },
        audioFiles::observeLocalBytes, questions.holds, TitleHandover.state,
    )

    /** What the player side holds for [sid]: the controller's title and everything else (see [PlayerHold]). */
    private fun holdOf(sid: String, loaded: String?, queued: List<String>, questions: Set<FinishedQuestion>, h: TitleHandover.State) = PlayerHold(
        loaded,
        queued.toSet() + questions.filter { it.serverId == sid }.map { it.key } + listOfNotNull(h.serviceTitle),
        h.picked,
    )

    /** Seam for tests: how many passes ran. */
    @Volatile internal var passes = 0
        private set

    /** One pass for the signed-in account (the daily worker); returns the keys whose downloads were deleted. */
    suspend fun runOnce(): List<String> {
        val sid = accounts.account.first()?.serverId ?: return emptyList()
        // What holds is read before the keep list: a question settled in between is then still held or already kept.
        val hold = holdOf(sid, loadedKey.first(), queue(sid).first(), questionHolds.first(), handover.first())
        return pass(
            sid, downloads.observeFinishedComplete(sid).first(), finishedKeys(sid).first().toSet(), settings.autoDeleteFinished(sid).first(),
            app.cleanupIgnored.first(), hold, Departures(graceMs),
        ).deleted
    }

    /** What [mode] would delete of today's finished downloads, now or once due (titles the player holds included). */
    suspend fun preview(mode: AutoDeleteMode): AutoDeletePreview {
        if (mode == AutoDeleteMode.NEVER) return AutoDeletePreview(0, 0)
        val sid = accounts.account.first()?.serverId ?: return AutoDeletePreview(0, 0)
        val seenAt = seen.reconcile(sid, finishedKeys(sid).first().toSet(), clock())
        val keep = AutoDeleteRules.keeper(sid, app.cleanupIgnored.first(), emptySet())
        val keys = candidates(sid, downloads.observeFinishedComplete(sid).first(), seenAt, keep).filter { AutoDeleteRules.counts(it, keep) }.map { it.key }.toSet()
        val bytes = localBytes(sid).first().filter { it.itemId in keys }.sumOf { it.bytes }
        return AutoDeletePreview(keys.size, bytes)
    }

    /**
     * Watches as long as [scope] lives. Passes run on changes only – the finished downloads, the setting, the kept list,
     * the player's title, the queue, the questions, the hand-over – plus one timer: the next due time or the end of a
     * pick or grace that holds a title which would count. An error is logged once per streak and the pass is tried again.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            val departures = Departures(graceMs)
            val failing = java.util.concurrent.atomic.AtomicBoolean(false)
            accounts.account.map { it?.serverId }.distinctUntilChanged().flatMapLatest { sid ->
                if (sid == null) emptyFlow()
                else combine(
                    downloads.observeFinishedComplete(sid).distinctUntilChanged(),
                    finishedKeys(sid).map { it.toSet() }.distinctUntilChanged(),
                    settings.autoDeleteFinished(sid),
                    app.cleanupIgnored,
                    combine(loadedKey.distinctUntilChanged(), queue(sid).distinctUntilChanged(), questionHolds, handover) { l, q, qs, h -> holdOf(sid, l, q, qs, h) },
                ) { rows, finished, mode, kept, hold -> Watch(sid, rows, finished, mode, kept, hold) }
                    .retryWhen { e, _ ->
                        // A store that cannot be read (a full disk) pauses the clean-up; it reads again a little later.
                        if (e is CancellationException) return@retryWhen false
                        if (failing.compareAndSet(false, true)) Log.w(TAG, "automatic clean-up could not read its state; tried again", e)
                        delay(recheckMs)
                        true
                    }
            }
                // Every change of what the player holds is noted, also one the pass below never sees (it is restarted).
                .onEach { w -> clock().let { now -> departures.note(w.hold.keys(now, pickMs), now) } }
                .collectLatest { w ->
                    while (true) {
                        val next = try {
                            pass(w.serverId, w.rows, w.finished, w.mode, w.kept, w.hold, departures).nextCheck.also { failing.set(false) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (failing.compareAndSet(false, true)) Log.w(TAG, "an automatic clean-up pass failed; tried again", e)
                            clock() + recheckMs
                        } ?: break
                        delay((next - clock()).coerceAtLeast(1_000))
                    }
                }
        }
    }

    /**
     * Titles that left the player's hold (the queue, the loaded or the service's title, a picked one) stay held for
     * [graceMs] more: whatever order the player, the queue and this watcher see a hand-over in, no gap opens.
     */
    private class Departures(private val graceMs: Long) {
        private var held: Set<String> = emptySet()
        private val leftAt = HashMap<String, Long>()

        @Synchronized fun note(now: Set<String>, at: Long) {
            (held - now).forEach { leftAt[it] = at }
            now.forEach { leftAt.remove(it) }
            held = now
            leftAt.values.removeAll { at - it >= graceMs }
        }

        @Synchronized fun recent(at: Long): Map<String, Long> = leftAt.filterValues { at - it < graceMs }.mapValues { it.value + graceMs }
    }

    private class Watch(
        val serverId: String,
        val rows: List<FinishedDownload>,
        val finished: Set<String>,
        val mode: AutoDeleteMode,
        val kept: Set<String>,
        val hold: PlayerHold,
    )

    /**
     * What the player holds: [loaded] (the controller's title, any state; null when it knows none), [steady] (the queue,
     * unsettled questions, the service's title – each ends with a change the watcher sees) and [picked] (key → when it
     * was picked; it holds for the grace, judged at the time of each pass). Without a loaded title the service's last
     * played one stands in for it ([LastPlayedStore]): a process the daily worker started.
     */
    private data class PlayerHold(val loaded: String?, val steady: Set<String>, val picked: Map<String, Long>) {
        fun picksAt(now: Long, graceMs: Long): Map<String, Long> = picked.filterValues { now - it < graceMs }
        fun keys(now: Long, graceMs: Long): Set<String> = steady + listOfNotNull(loaded) + picksAt(now, graceMs).keys
    }

    private class PassResult(val deleted: List<String>, val nextCheck: Long?)

    private suspend fun pass(
        sid: String, rows: List<FinishedDownload>, finished: Set<String>, mode: AutoDeleteMode, kept: Set<String>, hold: PlayerHold,
        departures: Departures,
    ): PassResult {
        passes++
        val seenAt = seen.reconcile(sid, finished, clock())
        if (mode == AutoDeleteMode.NEVER || rows.isEmpty()) return PassResult(emptyList(), null)
        val now = clock()
        val title = hold.loaded ?: lastPlayed()?.takeIf { it.serverId == sid }?.itemId
        val steady = hold.steady + listOfNotNull(title)
        val picks = hold.picksAt(now, pickMs).mapValues { it.value + pickMs } // key → until
        val recent = departures.recent(now) // key → until
        val protected = steady + picks.keys + recent.keys
        val keepIgnoringPlayer = AutoDeleteRules.keeper(sid, kept, emptySet())
        val keep = AutoDeleteRules.keeper(sid, kept, protected)
        val candidates = candidates(sid, rows, seenAt, keepIgnoringPlayer)
        val deleted = delete(sid, AutoDeleteRules.due(candidates, mode, now, keep))
        // The one timer: a title that would count and is held only by a pick or a grace is looked at when that ends.
        // What the queue, the player or a question holds ends with a change the watcher sees anyway.
        val lapse = candidates.filter { it.key !in steady && AutoDeleteRules.counts(it, keepIgnoringPlayer) }
            .mapNotNull { c -> listOfNotNull(picks[c.key], recent[c.key]).maxOrNull() }.filter { it > now }.minOrNull()
        return PassResult(deleted, listOfNotNull(AutoDeleteRules.nextDue(candidates, mode, now, keep), lapse).minOrNull())
    }

    /** The device-clock facts of each row; the file times are only read for rows that can count at all. */
    private suspend fun candidates(sid: String, rows: List<FinishedDownload>, seenAt: Map<String, Long>, keep: (String) -> Boolean) =
        rows.map { r ->
            // A baseline row (finished before this device looked) never counts: its files are not even looked at.
            val maybe = (seenAt[r.itemKey] ?: -1) >= 0 && !keep(r.itemKey) && AutoDeleteRules.reallyFinished(r.positionSec, r.durationSec)
            AutoDeleteCandidate(r.itemKey, r.positionSec, r.durationSec, if (maybe) completedAt(sid, r.itemKey) else null, seenAt[r.itemKey])
        }

    /** Each deletion finishes even if a newer state arrives meanwhile; one that fails never stops the others. */
    private suspend fun delete(serverId: String, keys: List<String>): List<String> {
        if (keys.isEmpty()) return keys
        val done = withContext(NonCancellable) {
            keys.filter { key ->
                try {
                    manager.cancel(serverId, key, deleteFiles = true)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "a finished download could not be deleted; tried again later", e)
                    false
                }
            }
        }
        if (done.isNotEmpty()) Log.i(TAG, "deleted ${done.size} finished downloads")
        return done
    }

    private companion object {
        const val TAG = "FinishedDownloads"
    }
}

/** Once a day: the automatic clean-up while the app is not running (same guards as in the app). */
@HiltWorker
class FinishedDownloadsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cleaner: FinishedDownloadCleaner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        cleaner.runOnce()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A store or the disk failed (often when space is short): try again, never give up for good.
        Log.w("FinishedDownloads", "daily clean-up failed; retried", e)
        Result.retry()
    }

    companion object {
        private const val WORK = "finished-downloads"

        /** Idempotent; called on every app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FinishedDownloadsWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
