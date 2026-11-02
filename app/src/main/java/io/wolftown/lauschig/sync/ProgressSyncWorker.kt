package io.wolftown.lauschig.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Progress-only run (dirty progress, offline sessions, newest server progress; no library pages) a few seconds after a
 * pause or the end of a title, so other devices see the position without waiting for the periodic [SyncWorker].
 */
@HiltWorker
class ProgressSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val progressSync: ProgressSync,
    private val bus: PositionJumpBus,
    private val gate: OnlineGate,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = accounts.account.first()?.serverId ?: return Result.success()
        if (!gate.awaitOnline()) return Result.retry()
        return try {
            progressSync.sync(serverId).forEach(bus::emit)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry() // dirty rows and sessions stay
        }
    }
}
