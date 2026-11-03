package io.wolftown.lauschig.download

import io.wolftown.lauschig.local.DeviceLibrary
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(val state: DownloadState, val bytesDone: Long, val bytesTotal: Long)

interface DownloadManager {
    /**
     * Queues the download (unique per item; no-op if already queued/running/complete). [network] overrides the
     * Wi-Fi-only setting; a still waiting download with another network condition is replaced, a running one is kept.
     */
    suspend fun enqueue(serverId: String, itemId: String, network: NetworkType? = null)

    /** Cancels the work. `deleteFiles=false` keeps the `.part` data and marks the item PAUSED; `true` removes files and rows. */
    suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean)

    /** `null` = no download row (never queued, or removed). */
    fun observe(serverId: String, itemId: String): Flow<DownloadProgress?>
}

@Singleton
class DefaultDownloadManager(
    private val context: Context,
    private val downloads: DownloadDao,
    private val audioFiles: AudioFileDao,
    private val files: DownloadFiles,
    private val settings: DownloadSettings,
    /** File deletion runs here: callers (view models) are on the main thread. */
    private val io: CoroutineDispatcher,
) : DownloadManager {
    @Inject constructor(
        @ApplicationContext context: Context,
        downloads: DownloadDao,
        audioFiles: AudioFileDao,
        files: DownloadFiles,
        settings: DownloadSettings,
    ) : this(context, downloads, audioFiles, files, settings, Dispatchers.IO)

    override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) {
        if (DeviceLibrary.isLocal(serverId, itemId)) return // already on the device; no server has it
        val existing = downloads.get(serverId, itemId)
        if (existing?.state == DownloadState.COMPLETE) return
        if (existing == null || existing.state == DownloadState.PAUSED || existing.state == DownloadState.FAILED) {
            // A resumed or retried download keeps the date it was first added (sorting on the downloads page).
            downloads.upsert(DownloadEntity(serverId, itemId, DownloadState.QUEUED, existing?.requestedAt ?: System.currentTimeMillis()))
        }
        val required = network ?: if (settings.wifiOnly.first()) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(DownloadWorker.KEY_SERVER to serverId, DownloadWorker.KEY_ITEM to itemId, DownloadWorker.KEY_NETWORK to required.name),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(required).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val wm = WorkManager.getInstance(context)
        val name = workName(serverId, itemId)
        val pending = wm.getWorkInfosForUniqueWorkFlow(name).first().firstOrNull { !it.state.isFinished }
        val policy = if (replaces(pending?.state, pending?.constraints?.requiredNetworkType, network)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(name, policy, request)
    }

    override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {
        // Never touches a device title: its "files" are the user's own documents.
        if (DeviceLibrary.isLocal(serverId, itemId)) return
        // Row first, then the work: a stopping worker's conditional state writes then cannot override it.
        if (deleteFiles) downloads.delete(serverId, itemId)
        else downloads.setState(serverId, itemId, DownloadState.PAUSED, null)
        WorkManager.getInstance(context).cancelUniqueWork(workName(serverId, itemId))
        if (deleteFiles) {
            audioFiles.clearLocal(serverId, itemId)
            // Blocking file-system work; callers run on the main thread ("delete finished" removes many titles).
            withContext(io) {
                files.deleteItem(serverId, itemId)
                files.cleanupOrphans(serverId, itemId)
            }
        }
    }

    override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> =
        combine(downloads.observe(serverId, itemId), audioFiles.observeBytes(serverId, itemId)) { d, b ->
            d?.let { DownloadProgress(it.state, b.done, b.total) }
        }

    private fun workName(serverId: String, itemId: String) = "download-$serverId-$itemId"

    companion object {
        /** Only a waiting download is retargeted to the [wanted] network; a running one keeps going as it is. */
        fun replaces(state: WorkInfo.State?, current: NetworkType?, wanted: NetworkType?): Boolean =
            wanted != null && current != wanted && (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {
    @Binds abstract fun manager(impl: DefaultDownloadManager): DownloadManager
}
