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
    private val gate: OnlineGate,
    private val autoDownloader: EpisodeAutoDownloader,
    private val bookmarkSync: BookmarkSync,
    private val collectionSync: CollectionSync,
    private val listeningSync: ListeningSessionSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = accounts.account.first()?.serverId ?: return Result.success()
        if (!gate.awaitOnline()) return Result.retry()
        var ok = true
        try {
            librarySync.syncAll(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
        }
        try {
            bus.emitFor(serverId, accounts, progressSync.sync(serverId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false
        }
        try {
            bookmarkSync.sync(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ok = false // the outbox stays; retry
        }
        try {
            collectionSync.syncIfDue(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the cached collections stay, the next run tries again.
        }
        // Local work, no API to refuse it: a switch in the meantime must not queue the old account's episodes.
        if (ok && accounts.account.first()?.serverId == serverId) {
            try {
                autoDownloader.run(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: the next successful sync tries again.
            }
        }
        try {
            // Last: a long first run of the statistics must not hold back new episodes. After the progress sync, so
            // the sessions this device just uploaded are on the server when the newest ones are read.
            listeningSync.syncIfDue(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: the statistics keep their cache, the next run tries again.
        }
        return if (ok) Result.success() else Result.retry()
    }
}
