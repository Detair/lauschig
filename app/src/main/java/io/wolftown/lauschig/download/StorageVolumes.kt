package io.wolftown.lauschig.download

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of storage a [DownloadVolume] is; only for its label. */
enum class VolumeKind { INTERNAL, SD_CARD, EXTERNAL }

/**
 * One storage medium downloads can live on. [path] is the app's own files directory there (`filesDir` internally,
 * `<volume>/Android/data/<package>/files` on a card); it is what the setting stores. Downloads go to [downloadRoot].
 * [label] is the system's name for the medium ("SanDisk SD card"), null if it has none.
 */
data class DownloadVolume(val path: File, val kind: VolumeKind, val label: String?, val writable: Boolean) {
    val downloadRoot: File get() = File(path, DOWNLOADS)

    companion object {
        const val DOWNLOADS = "downloads"
    }
}

/** The system's view of a directory's volume; a seam for tests. */
interface VolumeProbe {
    /** [Environment.getExternalStorageState] of the volume holding [dir]. */
    fun state(dir: File): String
    fun isEmulated(dir: File): Boolean
    fun isRemovable(dir: File): Boolean
    fun description(dir: File): String?
}

class AndroidVolumeProbe(private val context: Context) : VolumeProbe {
    override fun state(dir: File): String = Environment.getExternalStorageState(dir)
    override fun isEmulated(dir: File): Boolean = Environment.isExternalStorageEmulated(dir)
    override fun isRemovable(dir: File): Boolean = Environment.isExternalStorageRemovable(dir)
    override fun description(dir: File): String? =
        context.getSystemService(StorageManager::class.java)?.getStorageVolume(dir)?.getDescription(context)?.takeIf { it.isNotBlank() }
}

/**
 * The storage media of this device the app may keep downloads on: the internal storage (always) and every other
 * mounted volume the system gives the app a files directory on (`getExternalFilesDirs`: SD cards, sometimes USB drives).
 * The primary "external" storage is skipped: it is emulated on the internal flash, a second "internal" choice would only
 * confuse. A read-only volume is listed (its downloads can still be played), but not [DownloadVolume.writable].
 * The list of other volumes is cached for [ttlMs] (asking the system is a binder call and creates directories);
 * a card pulled meanwhile only makes file operations on it fail, which every caller handles.
 */
@Singleton
class StorageVolumes(
    private val internalDir: File,
    private val externalDirs: () -> List<File?>,
    private val probe: VolumeProbe,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val ttlMs: Long = 10_000,
) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.filesDir, { ContextCompat.getExternalFilesDirs(context, null).toList() }, AndroidVolumeProbe(context))

    @Volatile private var cached: Pair<Long, List<DownloadVolume>>? = null

    fun internal(): DownloadVolume = DownloadVolume(internalDir, VolumeKind.INTERNAL, null, writable = true)

    /** Every other volume that can be read right now, in the system's order. Blocking. */
    fun external(): List<DownloadVolume> {
        val now = clock()
        cached?.let { (at, list) -> if (now - at in 0 until ttlMs) return list }
        val list = scan()
        cached = now to list
        return list
    }

    /** [internal] first, then [external]. Blocking. */
    fun all(): List<DownloadVolume> = listOf(internal()) + external()

    /** Forgets the cached list (after the user picked a volume: its state is read anew). */
    fun refresh() { cached = null }

    private fun scan(): List<DownloadVolume> = try {
        externalDirs().filterNotNull().mapNotNull { dir ->
            try {
                if (probe.isEmulated(dir)) return@mapNotNull null
                val state = probe.state(dir)
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
                val kind = if (probe.isRemovable(dir)) VolumeKind.SD_CARD else VolumeKind.EXTERNAL
                DownloadVolume(dir, kind, probe.description(dir), writable = state == Environment.MEDIA_MOUNTED && dir.canWrite())
            } catch (e: IllegalArgumentException) {
                null // not a volume the system knows (any more)
            }
        }.distinctBy { it.path.absolutePath }
    } catch (e: Exception) {
        emptyList() // the storage service is unreachable: internal only
    }
}
