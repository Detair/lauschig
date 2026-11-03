package io.wolftown.lauschig.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers for other apps (Android Auto, SystemUI's media controls and resumption card): they can neither read app
 * storage nor send the server token. Read-only, never exported; callers get a prefix grant (see [CarAccess]). Only
 * [CoverUris] of well-formed ids resolve, through [CoverStore]. The binder thread never waits for the network: a cover
 * on its way is a pipe, returned at once and filled once the fetch delivers (closed with an error if nothing comes
 * within [WAIT_MS]) – Android Auto remembers a failed load per URI, and asking it again means reloading its lists,
 * which throws the driver back to the top of a grid.
 */
class CoverProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun coverStore(): CoverStore
    }

    private val store by lazy { EntryPointAccessors.fromApplication(context!!.applicationContext, Deps::class.java).coverStore() }
    private val authority by lazy { CoverUris.authority(context!!.packageName) }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("covers are read-only")
        val ref = CoverUris.parse(uri, authority) ?: throw FileNotFoundException("not a cover")
        store.file(ref)?.let { return open(it) }
        // Our own process (Media3's notification artwork, one shared loader thread) is not made to wait: it gets the
        // playing title's artwork under a fresh URI once the cover arrived (see PlaybackService.coverArrived).
        if (Binder.getCallingUid() == Process.myUid()) throw FileNotFoundException("no cover yet")
        val sink = try {
            PipeSink.create()
        } catch (e: IOException) {
            throw FileNotFoundException("no pipe: ${e.message}")
        }
        return try {
            CoverPipe.open(store, ref, writer, { task -> timer.schedule(task, WAIT_MS, TimeUnit.MILLISECONDS) }, sink)?.read
                ?: throw FileNotFoundException("no cover")
        } catch (e: RuntimeException) {
            sink.discard()
            throw e
        }
    }

    private fun open(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String? = if (CoverUris.parse(uri, authority) != null) "image/jpeg" else null

    /** Name and size only (some image loaders ask before opening); the size is unknown while the cover is on its way. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ref = CoverUris.parse(uri, authority) ?: return null
        val file = store.file(ref)
        if (file == null && !store.pending(ref)) return null // nothing will come
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file?.length() else "cover.jpg" })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    private companion object {
        /** How long a pipe waits for its cover; the fetches of a whole page queue behind [CoverStore.MAX_FETCHES]. */
        const val WAIT_MS = 30_000L

        /** Copies arrived covers into their pipes (a write blocks while the reader is slow); threads end when idle. */
        val writer: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "cover-pipes").apply { isDaemon = true } }
        val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cover-pipe-timeouts").apply { isDaemon = true } }
    }
}

/** Where a cover on its way is written to: the pipe in the app, a recording fake in tests. */
internal interface CoverSink {
    /** Hands [file] to the reader and closes; a failure fails the reader. */
    fun send(file: File)

    /** Closes with an error: the reader gets no picture. */
    fun fail(message: String)

    /** Never handed out (nothing will come): closes every end. */
    fun discard()
}

/** The pipe for a cover on its way, see [CoverProvider]. */
internal object CoverPipe {
    /**
     * [sink], answered exactly once – with the cover, or failed when none comes or [giveUpLater] ran its task first –
     * or null (and [sink] discarded) if nothing will come; the caller then answers "not found".
     */
    fun <S : CoverSink> open(store: CoverStore, ref: CoverRef, writer: Executor, giveUpLater: (Runnable) -> Unit, sink: S): S? {
        val answered = AtomicBoolean(false)
        val answer = store.request(ref) { file ->
            if (!answered.compareAndSet(false, true)) return@request false
            writer.execute { if (file != null) sink.send(file) else sink.fail("no cover") }
            true
        }
        when (answer) {
            is CoverStore.Answer.Here -> if (answered.compareAndSet(false, true)) writer.execute { sink.send(answer.file) }
            CoverStore.Answer.None -> {
                sink.discard()
                return null
            }
            is CoverStore.Answer.Later -> giveUpLater {
                if (answered.compareAndSet(false, true)) {
                    answer.cancel()
                    sink.fail("timed out")
                }
            }
        }
        return sink
    }
}

/** A reliable pipe: [read] goes to the caller, the cover is written to the other end. */
internal class PipeSink private constructor(val read: ParcelFileDescriptor, private val write: ParcelFileDescriptor) : CoverSink {
    override fun send(file: File) {
        try {
            // Not closed through the stream: only a complete copy closes the pipe as a success.
            FileInputStream(file).use { input -> input.copyTo(FileOutputStream(write.fileDescriptor)) }
            write.close()
        } catch (e: IOException) {
            // The reader went away (the car scrolled on) or the file vanished (sign-out).
            fail("write failed")
        }
    }

    override fun fail(message: String) {
        try {
            write.closeWithError(message)
        } catch (e: IOException) {
            // Already closed.
        }
    }

    override fun discard() {
        listOf(read, write).forEach { end ->
            try {
                end.close()
            } catch (e: IOException) {
                // Already closed.
            }
        }
    }

    companion object {
        fun create(): PipeSink = ParcelFileDescriptor.createReliablePipe().let { (read, write) -> PipeSink(read, write) }
    }
}
