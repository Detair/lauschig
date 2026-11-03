package io.wolftown.lauschig.auto

import android.util.Log
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlayableKey
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The file behind a [CoverRef] for [CoverProvider]: the title's own cover on the device (download or device title)
 * first, else the server cover cached under [cacheDir], else — only if [fetch] can reach the server — fetched once,
 * cached and returned. Blocking (the provider runs on a binder thread); at most [MAX_FETCHES] fetches at a time, and a
 * cover is fetched once even when several requests ask for it at the same moment. Device titles are never fetched.
 */
class CoverStore(
    /** The title's cover on the device, if it exists (downloaded cover, a device title's scanned cover). */
    private val localCover: (serverId: String, key: String) -> File?,
    private val cacheDir: File,
    /** The cover bytes of the library item from the online server, or null (offline, no cover, error). Blocking. */
    private val fetch: (serverId: String, libraryItemId: String) -> ByteArray?,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val fetches = Semaphore(MAX_FETCHES)

    fun file(ref: CoverRef): File? {
        localCover(ref.serverId, ref.key)?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        if (DeviceLibrary.isLocal(ref.serverId, ref.key)) return null
        // Episodes show their podcast's cover; one cached file per podcast.
        val itemId = PlayableKey.parse(ref.key).libraryItemId
        val dir = File(cacheDir, ref.serverId)
        val target = File(dir, "$itemId@${ref.version}.jpg") // "@" never occurs in an id
        val lock = locks.getOrPut(target.path) { Any() }
        synchronized(lock) {
            if (target.isFile && target.length() > 0) return target
            if (!fetches.tryAcquire(FETCH_WAIT_SEC, TimeUnit.SECONDS)) return null
            val bytes = try {
                fetch(ref.serverId, itemId)
            } catch (e: Exception) {
                Log.w(TAG, "cover fetch failed", e)
                null
            } finally {
                fetches.release()
            }
            if (bytes == null || bytes.isEmpty()) return null
            return try {
                dir.mkdirs()
                val part = File(dir, target.name + ".part")
                part.writeBytes(bytes)
                if (!part.renameTo(target)) { part.delete(); return null }
                // Older versions of the same cover are stale now.
                dir.listFiles { f -> f.name.startsWith("$itemId@") && f.name != target.name }?.forEach { it.delete() }
                target
            } catch (e: IOException) {
                Log.w(TAG, "cover cache write failed", e)
                null
            }
        }
    }

    companion object {
        const val DIR = "car_covers"
        const val MAX_FETCHES = 2
        const val FETCH_WAIT_SEC = 15L
        /** A cover larger than this is not cached (and not shown). */
        const val MAX_BYTES = 8 * 1024 * 1024
        private const val TAG = "CoverStore"
    }
}
