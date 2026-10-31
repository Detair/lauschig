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

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val librarySync: LibrarySync,
    private val progressSync: ProgressSync,
    private val bus: PositionJumpBus,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = accounts.account.first()?.serverId ?: return Result.success()
        var ok = true
        try {
            librarySync.syncAll(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
        }
        try {
            progressSync.sync(serverId).forEach(bus::emit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
        }
        return if (ok) Result.success() else Result.retry()
    }
}
