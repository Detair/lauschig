package io.wolftown.lauschig.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wolftown.lauschig.absapi.library.audioFileUrl
import io.wolftown.lauschig.absapi.library.coverUrl
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.OnlineGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/** Connection + authenticated HTTP client for downloads (the same client the API uses). */
class DownloadEnvironment(val connection: Flow<ConnectionState>, val client: (HttpUrl) -> OkHttpClient) {
    @Inject constructor(apiProvider: ApiProvider) : this(apiProvider.connection, apiProvider::clientFor)
}

/**
 * Downloads the original audio files and the cover of one item. A file is written to `<name>.part` and only
 * renamed (atomically) and recorded as `localPath` once its size is verified, so an interrupted download
 * never counts as downloaded. The `.part` length on disk is the resume offset (HTTP `Range`); a server that
 * answers 200 instead of 206 makes the file restart from the beginning.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val env: DownloadEnvironment,
    private val gate: OnlineGate,
    private val librarySync: LibrarySync,
    private val items: ItemDao,
    private val audioFiles: AudioFileDao,
    private val downloads: DownloadDao,
    private val files: DownloadFiles,
    private val notifier: DownloadNotifier,
) : CoroutineWorker(context, params) {

    private lateinit var serverId: String
    private lateinit var itemId: String
    private var title = ""
    private var lastNotify = 0L

    override suspend fun doWork(): Result {
        serverId = inputData.getString(KEY_SERVER) ?: return Result.failure()
        itemId = inputData.getString(KEY_ITEM) ?: return Result.failure()
        val row = downloads.get(serverId, itemId)
        // Removed (cancel + delete) or paused by the user while this work was waiting: nothing to do.
        if (row == null || row.state == DownloadState.PAUSED) return Result.success()

        if (!gate.awaitOnline()) return back("offline")
        val online = withTimeoutOrNull(5_000) { env.connection.first { it is ConnectionState.Online } } as? ConnectionState.Online
            ?: return back("offline")
        val client = env.client(online.baseUrl)

        downloads.setState(serverId, itemId, DownloadState.RUNNING, null)
        title = items.get(serverId, itemId)?.title.orEmpty()
        try {
            var list = audioFiles.forItem(serverId, itemId)
            if (list.isEmpty()) {
                librarySync.loadDetails(serverId, itemId)
                list = audioFiles.forItem(serverId, itemId)
                if (list.isEmpty()) throw IOException("No audio files known for item")
            }
            val total = list.sumOf { it.sizeBytes }
            var before = 0L
            notify(0, total, force = true)
            for (f in list) {
                downloadFile(client, online.baseUrl, f) { done -> notify(before + done, total, force = false) }
                before += f.sizeBytes
            }
            downloadCover(client, online.baseUrl)
            files.cleanupOrphans(serverId, itemId)
            downloads.setState(serverId, itemId, DownloadState.COMPLETE, null)
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped by WorkManager (constraints/system) -> back to QUEUED; a user pause/removal already changed the row.
            withContext(NonCancellable) { downloads.setStateIf(serverId, itemId, DownloadState.RUNNING, DownloadState.QUEUED, null) }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            return if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                downloads.setState(serverId, itemId, DownloadState.FAILED, msg)
                Result.failure()
            } else {
                back(msg)
            }
        }
    }

    private suspend fun back(message: String): Result {
        downloads.setStateIf(serverId, itemId, DownloadState.RUNNING, DownloadState.QUEUED, message)
        downloads.setStateIf(serverId, itemId, DownloadState.QUEUED, DownloadState.QUEUED, message)
        return Result.retry()
    }

    private suspend fun notify(done: Long, total: Long, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < NOTIFY_INTERVAL_MS) return
        lastNotify = now
        try {
            setForeground(notifier.info(itemId, title, done, total))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Foreground start may be refused (e.g. started from background); the download still runs.
        }
    }

    private suspend fun downloadFile(client: OkHttpClient, base: HttpUrl, f: AudioFileEntity, onProgress: suspend (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = files.audioFile(serverId, itemId, f.index, f.ext)
            target.parentFile!!.mkdirs()
            val known = f.sizeBytes > 0
            if (f.localPath != null) {
                if (f.localPath == target.path && target.isFile && (!known || target.length() == f.sizeBytes)) {
                    onProgress(target.length()); return@withContext
                }
                audioFiles.setLocal(serverId, itemId, f.index, null, 0) // recorded file is gone or wrong size
            }
            val part = File(target.path + ".part")
            var existing = if (part.isFile) part.length() else 0L
            if (known && existing > f.sizeBytes) { part.delete(); existing = 0 }

            var total = existing
            try {
                if (!(known && existing == f.sizeBytes)) {
                    val req = Request.Builder().url(audioFileUrl(base, itemId, f.ino))
                        .apply { if (existing > 0) header("Range", "bytes=$existing-") }.build()
                    client.newCall(req).execute().use { resp -> total = receive(resp, part, existing, onProgress) }
                }
                if (known && total != f.sizeBytes) {
                    if (total > f.sizeBytes) part.delete()
                    throw IOException("Size mismatch for file ${f.index}: $total of ${f.sizeBytes}")
                }
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                audioFiles.setLocal(serverId, itemId, f.index, target.absolutePath, total)
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    audioFiles.setProgress(serverId, itemId, f.index, if (part.isFile) part.length() else 0L)
                }
                throw t
            }
        }

    /** Streams the response into [part]; returns the resulting total length of the part file. */
    private suspend fun receive(resp: Response, part: File, existing: Long, onProgress: suspend (Long) -> Unit): Long {
        val append: Boolean
        when (resp.code) {
            206 -> {
                val start = resp.header("Content-Range")?.let { RANGE_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (start != existing) { part.delete(); throw IOException("Unexpected Content-Range start $start, wanted $existing") }
                append = true
            }
            200 -> append = false // no range support (or fresh download): restart the file
            416 -> { part.delete(); throw IOException("Range not satisfiable, restarting file") }
            else -> throw IOException("HTTP ${resp.code}")
        }
        val body = resp.body
        var written = if (append) existing else 0L
        val ctx = currentCoroutineContext()
        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    ctx.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written)
                }
            }
        }
        return written
    }

    private suspend fun downloadCover(client: OkHttpClient, base: HttpUrl) = withContext(Dispatchers.IO) {
        val cover = files.coverFile(serverId, itemId)
        if (cover.isFile && cover.length() > 0) return@withContext
        cover.parentFile!!.mkdirs()
        val part = File(cover.path + ".part")
        client.newCall(Request.Builder().url(coverUrl(base, itemId)).build()).execute().use { resp ->
            if (resp.code == 404) return@withContext // item without a cover
            if (!resp.isSuccessful) throw IOException("Cover HTTP ${resp.code}")
            FileOutputStream(part).use { out -> resp.body.byteStream().use { it.copyTo(out) } }
        }
        Files.move(part.toPath(), cover.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val KEY_SERVER = "serverId"
        const val KEY_ITEM = "itemId"
        const val MAX_ATTEMPTS = 5
        private const val BUFFER = 64 * 1024
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private val RANGE_START = Regex("""bytes\s+(\d+)-""")
    }
}
