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
import kotlinx.coroutines.CoroutineScope
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
) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Schedules the periodic sync and a one-time sync on every Offline -> Online transition. */
    fun start(scope: CoroutineScope) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
        scope.launch {
            onlineTransitions(apiProvider.connection).collect {
                wm.enqueueUniqueWork(
                    ON_ONLINE, ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
                )
            }
        }
    }

    companion object {
        const val PERIODIC = "sync-periodic"
        const val ON_ONLINE = "sync-on-online"

        /** Emits once per Offline -> Online transition (the upstream's initial Offline counts as the "from"). */
        fun onlineTransitions(connection: Flow<ConnectionState>): Flow<Unit> =
            connection.map { it is ConnectionState.Online }.distinctUntilChanged().filter { it }.map { }
    }
}
