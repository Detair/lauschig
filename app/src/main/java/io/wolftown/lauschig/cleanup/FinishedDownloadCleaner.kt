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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 *   state, last played on this device, in the queue, or waiting for the "Download löschen?" answer), a device title.
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
        val seen = c.finishedSeenAt ?: return false
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val recheckMs: Long = 60_000,
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
    ) : this(
        accounts, downloads, progress::observeFinishedKeys, settings, app, manager, files::completedAt, seen,
        player.state.map { it.itemId }, lastPlayed::get, { sid -> queue.observe(sid).map { rows -> rows.map { it.itemKey } } },
        audioFiles::observeLocalBytes,
    )

    /** Keys whose "Download löschen?" question is open: never deleted before the answer. */
    private val held = MutableStateFlow<Set<String>>(emptySet())

    fun hold(key: String) = held.update { it + key }
    fun release(key: String) = held.update { it - key }

    /** One pass for the signed-in account (the daily worker); returns the keys whose downloads were deleted. */
    suspend fun runOnce(): List<String> {
        val sid = accounts.account.first()?.serverId ?: return emptyList()
        val queued = queue(sid).first().toSet()
        return pass(
            sid, downloads.observeFinishedComplete(sid).first(), finishedKeys(sid).first().toSet(), settings.autoDeleteFinished.first(),
            app.cleanupIgnored.first(), setOfNotNull(loadedKey.first()) + queued + held.value,
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

    /** Watches as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                accounts.account.map { it?.serverId }.distinctUntilChanged().flatMapLatest { sid ->
                    if (sid == null) emptyFlow()
                    else combine(
                        downloads.observeFinishedComplete(sid).distinctUntilChanged(),
                        finishedKeys(sid).map { it.toSet() }.distinctUntilChanged(),
                        settings.autoDeleteFinished,
                        app.cleanupIgnored,
                        combine(loadedKey.distinctUntilChanged(), queue(sid).distinctUntilChanged(), held) { l, q, h -> setOfNotNull(l) + q + h },
                    ) { rows, finished, mode, kept, protected -> Watch(sid, rows, finished, mode, kept, protected) }
                }.collectLatest { w ->
                    while (true) {
                        val next = pass(w.serverId, w.rows, w.finished, w.mode, w.kept, w.protected).nextCheck ?: break
                        delay((next - clock()).coerceAtLeast(1_000))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "automatic clean-up stopped; the daily run takes over", e)
            }
        }
    }

    private class Watch(
        val serverId: String,
        val rows: List<FinishedDownload>,
        val finished: Set<String>,
        val mode: AutoDeleteMode,
        val kept: Set<String>,
        val protected: Set<String>,
    )

    private class PassResult(val deleted: List<String>, val nextCheck: Long?)

    private suspend fun pass(
        sid: String, rows: List<FinishedDownload>, finished: Set<String>, mode: AutoDeleteMode, kept: Set<String>, playerKeys: Set<String>,
    ): PassResult {
        val seenAt = seen.reconcile(sid, finished, clock())
        if (mode == AutoDeleteMode.NEVER || rows.isEmpty()) return PassResult(emptyList(), null)
        val last = lastPlayed()?.takeIf { it.serverId == sid }?.itemId
        val protected = playerKeys + listOfNotNull(last)
        val keepIgnoringPlayer = AutoDeleteRules.keeper(sid, kept, emptySet())
        val keep = AutoDeleteRules.keeper(sid, kept, protected)
        val candidates = candidates(sid, rows, seenAt, keepIgnoringPlayer)
        val now = clock()
        val deleted = delete(sid, AutoDeleteRules.due(candidates, mode, now, keep))
        // A title the player holds waits; it is looked at again (the last played title changes without a signal here).
        val waiting = candidates.any { it.key in protected && AutoDeleteRules.counts(it, keepIgnoringPlayer) }
        val next = listOfNotNull(AutoDeleteRules.nextDue(candidates, mode, now, keep), if (waiting) now + recheckMs else null).minOrNull()
        return PassResult(deleted, next)
    }

    /** The device-clock facts of each row; the file times are only read for rows that can count at all. */
    private suspend fun candidates(sid: String, rows: List<FinishedDownload>, seenAt: Map<String, Long>, keep: (String) -> Boolean) =
        rows.map { r ->
            val maybe = seenAt[r.itemKey] != null && !keep(r.itemKey) && AutoDeleteRules.reallyFinished(r.positionSec, r.durationSec)
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
    override suspend fun doWork(): Result {
        cleaner.runOnce()
        return Result.success()
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
