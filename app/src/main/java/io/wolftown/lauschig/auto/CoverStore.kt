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
    /** The cover of the library item from the online server. Blocking. */
    private val fetch: (serverId: String, libraryItemId: String) -> Fetch,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Fetch {
        class Found(val bytes: ByteArray) : Fetch
        /** The server was asked and has none (or failed): not asked again for [MISS_TTL_MS]. */
        data object Missing : Fetch
        /** Nothing was asked (offline, another account): asked again next time. */
        data object Unavailable : Fetch
    }

    private val locks = ConcurrentHashMap<String, Any>()
    private val fetches = Semaphore(MAX_FETCHES)
    /** Covers the server did not deliver, until when not to ask again (SystemUI reloads artwork on every change). */
    private val misses = ConcurrentHashMap<String, Long>()

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
            misses[target.path]?.let { until -> if (clock() < until) return null }
            if (!fetches.tryAcquire(FETCH_WAIT_SEC, TimeUnit.SECONDS)) return null
            val result = try {
                fetch(ref.serverId, itemId)
            } catch (e: Exception) {
                // No answer (timeout, connection lost): try again soon, without hammering a dead link.
                Log.w(TAG, "cover fetch failed: $e")
                misses[target.path] = clock() + ERROR_TTL_MS
                return null
            } finally {
                fetches.release()
            }
            val bytes = (result as? Fetch.Found)?.bytes?.takeIf { it.isNotEmpty() }
            if (bytes == null) {
                if (result !is Fetch.Unavailable) misses[target.path] = clock() + MISS_TTL_MS
                return null
            }
            misses.remove(target.path)
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
        /** A title without a cover is asked for again after this long. */
        const val MISS_TTL_MS = 30 * 60_000L
        /** After a failed request (no answer) the cover is asked for again after this long. */
        const val ERROR_TTL_MS = 2 * 60_000L
        private const val TAG = "CoverStore"
    }
}
