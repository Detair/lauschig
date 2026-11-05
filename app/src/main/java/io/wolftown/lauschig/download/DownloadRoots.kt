package io.wolftown.lauschig.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The download roots besides [DownloadFiles]' internal one. */
interface DownloadRoots {
    /** Roots on other volumes that can be read right now, in a stable order. Blocking, cheap (cached). */
    fun external(): List<File>

    /** Where new downloads go: one of [external], or null for the internal root. Blocking, cheap. */
    fun target(): File?

    /** Suspends until [target] reflects the stored setting (right after a cold start it may not yet). */
    suspend fun awaitReady() {}

    /** The chosen volume's download root while that volume is not there (null: none chosen, or it is there). */
    fun missingTarget(): File? = null

    /** Internal storage only (tests, and every place that never sees another volume). */
    object None : DownloadRoots {
        override fun external(): List<File> = emptyList()
        override fun target(): File? = null
    }
}

/**
 * [DownloadRoots] from the device's volumes ([StorageVolumes]) and the chosen location ([DownloadSettings.storagePath]).
 * The chosen volume is the target only while it is mounted and writable; otherwise new downloads go to the internal
 * storage (the choice stays). Every external root gets a `.nomedia` file before the first download, so music apps on
 * older Android versions do not list the audiobook files.
 */
@Singleton
class VolumeDownloadRoots(
    private val volumes: StorageVolumes,
    private val chosen: () -> String?,
    private val ready: suspend () -> Unit = {},
) : DownloadRoots {
    @Inject constructor(volumes: StorageVolumes, settings: DownloadSettings) : this(volumes, ChosenPath(settings.storagePath))

    /** Seam for tests: the stored choice as a flow. */
    internal constructor(volumes: StorageVolumes, storagePath: Flow<String?>) : this(volumes, ChosenPath(storagePath))

    private constructor(volumes: StorageVolumes, path: ChosenPath) : this(volumes, path::current, path::awaitLoaded)

    private val marked = ConcurrentHashMap.newKeySet<String>()

    override fun external(): List<File> = volumes.external().map { it.downloadRoot }

    override fun target(): File? {
        val path = chosen() ?: return null
        val volume = volumes.external().firstOrNull { it.path.absolutePath == path && it.writable } ?: return null
        val root = volume.downloadRoot
        if (root.absolutePath !in marked) {
            try {
                root.mkdirs()
                File(root, NO_MEDIA).createNewFile()
                marked += root.absolutePath
            } catch (e: IOException) {
                return null // not writable after all: internal
            } catch (e: SecurityException) {
                return null
            }
        }
        return root
    }

    override suspend fun awaitReady() = ready()

    /** A volume is chosen but is not there (or not writable) now: new downloads go to the internal storage. Blocking. */
    fun chosenMissing(): Boolean = chosen() != null && target() == null

    override fun missingTarget(): File? = chosen()?.takeIf { target() == null }?.let { File(it, DownloadVolume.DOWNLOADS) }

    /** The stored choice, kept current; [current] never blocks (null until the first read). */
    private class ChosenPath(storagePath: Flow<String?>) {
        @Volatile private var value: String? = null
        private val loaded = CompletableDeferred<Unit>()

        init {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                // Whatever goes wrong reading the store, no download may wait for it: they go to the internal storage.
                try {
                    storagePath.collect { value = it; loaded.complete(Unit) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    value = null
                } finally {
                    loaded.complete(Unit)
                }
            }
        }

        fun current(): String? = value
        suspend fun awaitLoaded() = loaded.await()
    }

    companion object {
        const val NO_MEDIA = ".nomedia"
    }
}
