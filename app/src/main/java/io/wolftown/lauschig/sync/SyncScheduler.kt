package io.wolftown.lauschig.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiProvider: ApiProvider,
) : SyncTrigger {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Schedules the periodic sync and a one-time sync on every Offline -> Online transition. */
    fun start(scope: CoroutineScope) {
        val wm = WorkManager.getInstance(context)
        schedulePeriodic(wm)
        // Only probe while the app is visible; background syncs come from the periodic worker.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                onlineTransitions(apiProvider.connection).collect {
                    wm.enqueueUniqueWork(
                        ON_ONLINE, ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
                    )
                }
            }
        }
    }

    /**
     * One-time sync (e.g. right after login); returns immediately, the work runs in WorkManager. Also schedules the
     * periodic sync again if a sign-out cancelled it (KEEP: an existing schedule stays as it is).
     */
    override fun syncNow() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
        )
        schedulePeriodic(wm)
    }

    override fun nowRunning(): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW).map { infos -> infos.any { !it.state.isFinished } }.distinctUntilChanged()

    private fun schedulePeriodic(wm: WorkManager) {
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }

    /** Bookmark-only run; appended so a change made while one runs is pushed by the next one. */
    override fun syncBookmarks() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            BOOKMARKS, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BookmarkSyncWorker>().setConstraints(constraints).build(),
        )
    }

    override fun syncProgress() = enqueueProgressSync(WorkManager.getInstance(context))

    override fun syncLibrarySoon() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            AFTER_SCAN, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(AFTER_SCAN_DELAY_MS, TimeUnit.MILLISECONDS).setConstraints(constraints).build(),
        )
    }

    companion object {
        const val PROGRESS = "sync-progress"
        /** A pause right after another one (skip, resume, pause) only moves the run, so a burst syncs once. */
        const val PROGRESS_DEBOUNCE_MS = 10_000L

        fun enqueueProgressSync(wm: WorkManager) {
            wm.enqueueUniqueWork(
                PROGRESS, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                    .setInitialDelay(PROGRESS_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        const val BOOKMARKS = "sync-bookmarks"
        const val AFTER_SCAN = "sync-after-scan"
        /** A library scan of a few hundred titles usually finishes within a minute. */
        const val AFTER_SCAN_DELAY_MS = 60_000L
        const val NOW = "sync-now"
        const val PERIODIC = "sync-periodic"
        const val ON_ONLINE = "sync-on-online"

        /** Every sync work; all of it belongs to the signed-in account (sign-out cancels it). */
        val ACCOUNT_WORK = listOf(PERIODIC, NOW, ON_ONLINE, PROGRESS, BOOKMARKS, AFTER_SCAN)

        /** Emits once per Offline -> Online transition (the upstream's initial Offline counts as the "from"). */
        fun onlineTransitions(connection: Flow<ConnectionState>): Flow<Unit> =
            connection.map { it is ConnectionState.Online }.distinctUntilChanged().filter { it }.map { }
    }
}
