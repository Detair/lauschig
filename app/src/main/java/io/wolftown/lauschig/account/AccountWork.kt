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
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** The WorkManager work of one account. Device folder scans and the cleanup reminder are no account work. */
interface AccountWork {
    /**
     * The signed-in account [serverId]'s work: every sync (they are not per server – all of it is the signed-in
     * account's) and its own work ([cancelOwn]). Cancels it and waits (bounded) until WorkManager reports every piece
     * finished. The cancellation is recorded at once; a download that is just writing stops at its next chunk (it checks
     * before every one).
     */
    suspend fun cancel(serverId: String)

    /**
     * Only what is [serverId]'s own – its downloads and its work outside WorkManager – never the syncs: for an account
     * that is not the signed-in one (a saved profile), whose removal must not stop the signed-in account's syncs.
     */
    suspend fun cancelOwn(serverId: String)
}

/**
 * [cancel]: every sync ([SyncScheduler.ACCOUNT_WORK]; the periodic one comes back with the next `syncNow()`, which every
 * sign-in calls), and both: the account's downloads (by the download rows and by the server tag of newer download work), and a
 * „Jetzt laden“ read of the listening history, which runs outside WorkManager ([ListeningSessionSync.cancelLoadNow]).
 */
class WorkManagerAccountWork(
    private val workManager: () -> WorkManager,
    /** Keys of the server's download rows (any state). */
    private val downloadKeys: suspend (serverId: String) -> List<String>,
    private val waitMs: Long = WAIT_MS,
    /** Account work outside WorkManager. */
    private val cancelOther: (serverId: String) -> Unit = {},
) : AccountWork {
    @Inject constructor(@ApplicationContext context: Context, downloads: DownloadDao, listeningSync: ListeningSessionSync) :
        this({ WorkManager.getInstance(context) }, downloads::keys, WAIT_MS, listeningSync::cancelLoadNow)

    override suspend fun cancel(serverId: String) = cancel(serverId, syncs = true)

    override suspend fun cancelOwn(serverId: String) = cancel(serverId, syncs = false)

    private suspend fun cancel(serverId: String, syncs: Boolean) {
        cancelOther(serverId)
        val wm = workManager()
        val names = (if (syncs) SyncScheduler.ACCOUNT_WORK else emptyList()) +
            downloadKeys(serverId).map { DefaultDownloadManager.workName(serverId, it) }
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
