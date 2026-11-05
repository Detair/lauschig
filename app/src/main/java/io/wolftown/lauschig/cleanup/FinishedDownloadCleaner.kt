package io.wolftown.lauschig.cleanup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.FinishedDownload
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlayerController
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which finished downloads the automatic clean-up deletes ([AutoDeleteMode]). A download counts only if its title was
 * finished after the download was added: a title downloaded again to hear it once more is kept until it is finished
 * again. Kept: titles the user keeps ([AppSettings.cleanupIgnored], "Behalten" on the clean-up page), the title that is
 * playing, and titles of the device library (never a download; their files are the user's own documents).
 */
object AutoDeleteRules {
    fun due(rows: List<FinishedDownload>, mode: AutoDeleteMode, now: Long, keep: (String) -> Boolean): List<String> {
        val after = mode.afterMs ?: return emptyList()
        return rows.filter { counts(it, keep) && (after == 0L || now - it.finishedAt >= after) }.map { it.itemKey }
    }

    /** When the next download that is not due yet will be; null if none is waiting. */
    fun nextDue(rows: List<FinishedDownload>, mode: AutoDeleteMode, now: Long, keep: (String) -> Boolean): Long? {
        val after = mode.afterMs?.takeIf { it > 0 } ?: return null
        return rows.filter { counts(it, keep) }.map { it.finishedAt + after }.filter { it > now }.minOrNull()
    }

    private fun counts(row: FinishedDownload, keep: (String) -> Boolean) = row.finishedAt >= row.requestedAt && !keep(row.itemKey)

    /** "serverId:itemKey" entries the user keeps, the playing title, and every device title. */
    fun keeper(serverId: String, kept: Set<String>, playing: String?): (String) -> Boolean = { key ->
        "$serverId:$key" in kept || key == playing || DeviceLibrary.isLocal(serverId, key)
    }
}

/**
 * Deletes finished downloads of the signed-in account by [AutoDeleteRules] through [DownloadManager.cancel] (files and
 * the download row; progress, sessions and bookmarks stay). In the app it watches the finished downloads, the setting,
 * the kept list and the playing title and acts right away ("Sofort") or when the next one falls due; while the app is
 * not running [FinishedDownloadsWorker] runs it once a day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FinishedDownloadCleaner(
    private val accounts: AccountStore,
    private val downloads: DownloadDao,
    private val settings: DownloadSettings,
    private val app: AppSettings,
    private val manager: DownloadManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(accounts: AccountStore, downloads: DownloadDao, settings: DownloadSettings, app: AppSettings, manager: DownloadManager) :
        this(accounts, downloads, settings, app, manager, System::currentTimeMillis)

    /** One pass for the signed-in account; returns the keys whose downloads were deleted. */
    suspend fun runOnce(playing: String? = null): List<String> {
        val sid = accounts.account.first()?.serverId ?: return emptyList()
        val mode = settings.autoDeleteFinished.first()
        if (mode == AutoDeleteMode.NEVER) return emptyList()
        return delete(sid, AutoDeleteRules.due(downloads.observeFinishedComplete(sid).first(), mode, clock(), AutoDeleteRules.keeper(sid, app.cleanupIgnored.first(), playing)))
    }

    /** Watches as long as [scope] lives; [playing] is the key of the title playing right now (null: nothing plays). */
    fun start(scope: CoroutineScope, playing: Flow<String?>) {
        scope.launch {
            try {
                accounts.account.map { it?.serverId }.distinctUntilChanged().flatMapLatest { sid ->
                    if (sid == null) emptyFlow()
                    else combine(downloads.observeFinishedComplete(sid), settings.autoDeleteFinished, app.cleanupIgnored, playing.distinctUntilChanged()) { rows, mode, kept, now ->
                        Pass(sid, rows, mode, AutoDeleteRules.keeper(sid, kept, now))
                    }
                }.collectLatest { pass ->
                    while (true) {
                        delete(pass.serverId, AutoDeleteRules.due(pass.rows, pass.mode, clock(), pass.keep))
                        val next = AutoDeleteRules.nextDue(pass.rows, pass.mode, clock(), pass.keep) ?: break
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

    private class Pass(val serverId: String, val rows: List<FinishedDownload>, val mode: AutoDeleteMode, val keep: (String) -> Boolean)

    /** Each deletion finishes even if a newer state arrives meanwhile (its own deletion changes the watched rows). */
    private suspend fun delete(serverId: String, keys: List<String>): List<String> {
        if (keys.isEmpty()) return keys
        withContext(NonCancellable) { keys.forEach { manager.cancel(serverId, it, deleteFiles = true) } }
        Log.i(TAG, "deleted ${keys.size} finished downloads")
        return keys
    }

    private companion object {
        const val TAG = "FinishedDownloads"
    }
}

/** Once a day: the automatic clean-up while the app is not running. */
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

/** The key of the title the player is playing right now, for [FinishedDownloadCleaner.start]. */
fun PlayerController.playingKey(): Flow<String?> = state.map { if (it.isPlaying) it.itemId else null }
