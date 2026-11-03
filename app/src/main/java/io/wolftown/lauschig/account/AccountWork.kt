package io.wolftown.lauschig.account

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The WorkManager work of one account. Device folder scans and the cleanup reminder are no account work. */
fun interface AccountWork {
    /**
     * Cancels it and waits (bounded) until WorkManager reports every piece finished. The cancellation is recorded at
     * once; a download that is just writing stops at its next chunk (it checks before every one).
     */
    suspend fun cancel(serverId: String)
}

/**
 * Cancels every sync ([SyncScheduler.ACCOUNT_WORK]; the periodic one comes back with the next `syncNow()`, which every
 * sign-in calls) and the account's downloads: by the download rows and by the server tag of newer download work.
 */
class WorkManagerAccountWork(
    private val workManager: () -> WorkManager,
    /** Keys of the server's download rows (any state). */
    private val downloadKeys: suspend (serverId: String) -> List<String>,
    private val waitMs: Long = WAIT_MS,
) : AccountWork {
    @Inject constructor(@ApplicationContext context: Context, downloads: DownloadDao) :
        this({ WorkManager.getInstance(context) }, downloads::keys)

    override suspend fun cancel(serverId: String) {
        val wm = workManager()
        val names = SyncScheduler.ACCOUNT_WORK + downloadKeys(serverId).map { DefaultDownloadManager.workName(serverId, it) }
        val tag = DefaultDownloadManager.serverTag(serverId)
        names.forEach { wm.cancelUniqueWork(it) }
        wm.cancelAllWorkByTag(tag)
        // The cancel operations are asynchronous: wait until they are applied before the caller deletes anything.
        withTimeoutOrNull(waitMs) {
            names.forEach { name -> wm.getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } } }
            wm.getWorkInfosByTagFlow(tag).first { infos -> infos.all { it.state.isFinished } }
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountWorkModule {
    @Binds abstract fun accountWork(impl: WorkManagerAccountWork): AccountWork
}
