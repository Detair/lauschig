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
    private var lastPersist = 0L

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
            files.itemDir(serverId, itemId) // validates the server-controlled ids before anything touches the disk
            var list = audioFiles.forItem(serverId, itemId)
            if (list.isEmpty()) {
                librarySync.loadDetails(serverId, itemId)
                list = audioFiles.forItem(serverId, itemId)
                if (list.isEmpty()) throw IOException("No audio files known for item")
            }
            try {
                downloadAll(client, online.baseUrl, list)
            } catch (e: FatalDownloadException) {
                // 404: the server may have rescanned the item (new file ids). Reload the details once and retry.
                if (e.code != "http_404") throw e
                items.invalidateDetails(serverId, listOf(itemId))
                librarySync.loadDetails(serverId, itemId)
                val fresh = audioFiles.forItem(serverId, itemId)
                if (fresh.isEmpty()) throw e
                downloadAll(client, online.baseUrl, fresh)
            }
            // Best effort: a missing cover must not fail the book; the next download run retries it.
            try {
                downloadCover(client, online.baseUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            files.cleanupOrphans(serverId, itemId)
            downloads.setStateIf(serverId, itemId, DownloadState.RUNNING, DownloadState.COMPLETE, null)
            return Result.success()
        } catch (e: CancellationException) {
            // Stopped by WorkManager (constraints/system) -> back to QUEUED; a user pause/removal already changed the row.
            withContext(NonCancellable) { downloads.setStateIf(serverId, itemId, DownloadState.RUNNING, DownloadState.QUEUED, null) }
            throw e
        } catch (e: FatalDownloadException) {
            return fail(e.code)
        } catch (e: Exception) {
            val msg = if (isNoSpace(e)) return fail("storage_full") else e.message ?: e.javaClass.simpleName
            return if (runAttemptCount + 1 >= MAX_ATTEMPTS) fail(msg) else back(msg)
        }
    }

    private suspend fun downloadAll(client: OkHttpClient, base: HttpUrl, list: List<AudioFileEntity>) {
        val total = list.sumOf { it.sizeBytes }
        var before = 0L
        notify(0, total, force = true)
        for (f in list) {
            downloadFile(client, base, f) { done -> tick(f.index, done, before + done, total) }
            before += f.sizeBytes
        }
    }

    private suspend fun fail(code: String): Result {
        downloads.setStateIf(serverId, itemId, DownloadState.RUNNING, DownloadState.FAILED, code)
        return Result.failure()
    }

    private fun isNoSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space left", true) == true }

    /** Throttled (~1 s): persists the byte count of the file in flight and updates the notification. */
    private suspend fun tick(index: Int, fileDone: Long, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastPersist < NOTIFY_INTERVAL_MS) return
        lastPersist = now
        audioFiles.setProgress(serverId, itemId, index, fileDone)
        notify(done, total, force = true)
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
                    if (known && files.usableSpace(serverId, itemId) < f.sizeBytes - existing) throw FatalDownloadException("storage_full")
                    var start = existing
                    var result: Long? = null
                    for (attempt in 0..1) {
                        val req = Request.Builder().url(audioFileUrl(base, itemId, f.ino))
                            .apply { if (start > 0) header("Range", "bytes=$start-") }.build()
                        result = client.newCall(req).execute().use { resp -> receive(resp, part, start, onProgress) }
                        if (result != null) break
                        start = 0 // range refused or inconsistent: restart the file within this attempt
                    }
                    total = result ?: throw IOException("Could not restart file ${f.index}")
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
    private suspend fun receive(resp: Response, part: File, existing: Long, onProgress: suspend (Long) -> Unit): Long? {
        val append: Boolean
        when (resp.code) {
            206 -> {
                val start = resp.header("Content-Range")?.let { RANGE_START.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (start != existing) { part.delete(); return null }
                append = true
            }
            200 -> append = false // no range support (or fresh download): restart the file
            416 -> { part.delete(); return null }
            else -> throw httpError(resp.code)
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
            out.flush()
            out.fd.sync()
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
            if (!resp.isSuccessful) throw httpError(resp.code)
            FileOutputStream(part).use { out -> resp.body.byteStream().use { it.copyTo(out) } }
        }
        Files.move(part.toPath(), cover.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 4xx (except 408/429) will not get better by retrying; 5xx and the rest are transient. */
    private fun httpError(code: Int): Exception =
        if (code in 400..499 && code != 408 && code != 429) FatalDownloadException("http_$code") else IOException("http_$code")

    companion object {
        const val KEY_SERVER = "serverId"
        const val KEY_ITEM = "itemId"
        const val MAX_ATTEMPTS = 5
        private const val BUFFER = 64 * 1024
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private val RANGE_START = Regex("""bytes\s+(\d+)-""")
    }
}
