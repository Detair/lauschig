package io.wolftown.lauschig.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.db.dao.LocalFolderWithCount
import io.wolftown.lauschig.db.dao.LocalLibraryDao
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The folders of the device library: adding one takes the persisted read permission and scans; removing one deletes its
 * titles' catalog rows and releases the permission. Scans run in [LocalScanWorker] (no network constraint), on demand
 * and at most every [SCAN_EVERY_MS] on app start.
 */
@Singleton
class LocalFolders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LocalLibraryDao,
    private val tree: SafDocumentTree,
    private val scanner: LocalLibraryScanner,
    private val stamps: SyncStamps,
) {
    val folders: Flow<List<LocalFolderWithCount>> = dao.observeFolders()

    /** A scan is queued or running. */
    val scanning: Flow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .distinctUntilChanged()

    /** [uri] from `ACTION_OPEN_DOCUMENT_TREE`. False if the permission could not be kept. */
    suspend fun add(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable permission for the picked folder")
            return@withContext false
        }
        val key = uri.toString()
        val name = tree.root(key)?.name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: key
        dao.addFolder(LocalFolderEntity(key, name, addedAt = System.currentTimeMillis()))
        scanNow()
        true
    }

    suspend fun remove(uri: String) {
        scanner.removeFolder(uri)
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Already gone (revoked by the user or the system).
            }
        }
    }

    fun scanNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<LocalScanWorker>().build())
    }

    /** App start: scans if there are folders and the last scan is older than [SCAN_EVERY_MS]. Never blocks the start. */
    suspend fun scanIfDue(now: Long = System.currentTimeMillis()) {
        try {
            if (dao.folders().isNotEmpty() && isDue(stamps.get(STAMP), now)) scanNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not check the local scan", e)
        }
    }

    companion object {
        const val WORK = "local-scan"
        const val STAMP = "local_scan"
        const val SCAN_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val TAG = "LocalFolders"

        /** Never scanned, older than [SCAN_EVERY_MS], or a stamp from the future (clock changed). */
        fun isDue(last: Long?, now: Long): Boolean = last == null || now - last !in 0 until SCAN_EVERY_MS
    }
}

@HiltWorker
class LocalScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: LocalLibraryScanner,
    private val stamps: SyncStamps,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        scanner.scanAll()
        stamps.set(LocalFolders.STAMP, System.currentTimeMillis())
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("LocalScanWorker", "scan failed", e)
        Result.success() // the next start or "scan now" tries again; the titles stay as they were
    }
}
