package io.wolftown.lauschig.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(val state: DownloadState, val bytesDone: Long, val bytesTotal: Long)

interface DownloadManager {
    /** Queues the download (unique per item; no-op if already queued/running/complete). */
    suspend fun enqueue(serverId: String, itemId: String)

    /** Cancels the work. `deleteFiles=false` keeps the `.part` data and marks the item PAUSED; `true` removes files and rows. */
    suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean)

    /** Emits only while a download row exists (nothing is emitted for items that were never queued or were removed). */
    fun observe(serverId: String, itemId: String): Flow<DownloadProgress>
}

@Singleton
class DefaultDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloads: DownloadDao,
    private val audioFiles: AudioFileDao,
    private val files: DownloadFiles,
    private val settings: DownloadSettings,
) : DownloadManager {

    override suspend fun enqueue(serverId: String, itemId: String) {
        val existing = downloads.get(serverId, itemId)
        if (existing?.state == DownloadState.COMPLETE) return
        if (existing == null || existing.state == DownloadState.PAUSED || existing.state == DownloadState.FAILED) {
            downloads.upsert(DownloadEntity(serverId, itemId, DownloadState.QUEUED, System.currentTimeMillis()))
        }
        val network = if (settings.wifiOnly.first()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_SERVER to serverId, DownloadWorker.KEY_ITEM to itemId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(serverId, itemId), ExistingWorkPolicy.KEEP, request)
    }

    override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {
        // Row first, then the work: a stopping worker's conditional state writes then cannot override it.
        if (deleteFiles) downloads.delete(serverId, itemId)
        else downloads.setState(serverId, itemId, DownloadState.PAUSED, null)
        WorkManager.getInstance(context).cancelUniqueWork(workName(serverId, itemId))
        if (deleteFiles) {
            audioFiles.clearLocal(serverId, itemId)
            files.deleteItem(serverId, itemId)
            files.cleanupOrphans(serverId, itemId)
        }
    }

    override fun observe(serverId: String, itemId: String): Flow<DownloadProgress> =
        combine(downloads.observe(serverId, itemId).filterNotNull(), audioFiles.observeBytes(serverId, itemId)) { d, b ->
            DownloadProgress(d.state, b.done, b.total)
        }

    private fun workName(serverId: String, itemId: String) = "download-$serverId-$itemId"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {
    @Binds abstract fun manager(impl: DefaultDownloadManager): DownloadManager
}
