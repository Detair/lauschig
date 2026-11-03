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

/** Bookmark-only push and pull right after a local bookmark change (the full [SyncWorker] does not run for that). */
@HiltWorker
class BookmarkSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val gate: OnlineGate,
    private val bookmarkSync: BookmarkSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = accounts.account.first()?.serverId ?: return Result.success()
        if (!gate.awaitOnline()) return Result.retry()
        return try {
            bookmarkSync.sync(serverId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry() // the outbox stays
        }
    }
}
