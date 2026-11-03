package io.wolftown.lauschig.auto

import android.util.Log
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlayableKey
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * The file behind a [CoverRef] for [CoverProvider]: the title's own cover on the device (download or device title), or
 * the server cover cached under [cacheDir]. [file] never waits for the network – it runs on a binder thread of the car
 * or SystemUI: on a miss it answers "none" right away and fetches the cover on [executor] (each cover once, however
 * often it is asked), then tells [onFetched], so the service can make the car and the notification ask again.
 * Device titles are never fetched.
 */
class CoverStore(
    /** The title's cover on the device, if it exists (downloaded cover, a device title's scanned cover). */
    private val localCover: (serverId: String, key: String) -> File?,
    private val cacheDir: File,
    /** The cover of the library item from the online server. Blocking; runs on [executor]. */
    private val fetch: (serverId: String, libraryItemId: String) -> Fetch,
    /** Background fetches; a small bounded pool in the app. */
    private val executor: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Fetch {
        class Found(val bytes: ByteArray) : Fetch
        /** The server has no cover (404): not asked again for [MISS_TTL_MS]. */
        data object Missing : Fetch
        /** The request failed (5xx, timeout, connection lost): asked again after [ERROR_TTL_MS]. */
        data object Failed : Fetch
        /** Nothing was asked (offline, another account): asked again next time. */
        data object Unavailable : Fetch
    }

    /** A cover was fetched and cached (called on the [executor] thread). */
    @Volatile var onFetched: ((serverId: String, libraryItemId: String) -> Unit)? = null

    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Covers the server did not deliver, until when not to ask again (SystemUI reloads artwork on every change). */
    private val misses = ConcurrentHashMap<String, Long>()

    /** The cover file now, or null (and, for a server title, a fetch in the background). Never blocks on the network. */
    fun file(ref: CoverRef): File? {
        localCover(ref.serverId, ref.key)?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        if (DeviceLibrary.isLocal(ref.serverId, ref.key)) return null
        // Episodes show their podcast's cover; one cached file per podcast.
        val itemId = PlayableKey.parse(ref.key).libraryItemId
        val dir = File(cacheDir, ref.serverId)
        val target = File(dir, "$itemId@${ref.version}.jpg") // "@" never occurs in an id
        if (target.isFile && target.length() > 0) return target
        misses[target.path]?.let { until -> if (clock() < until) return null }
        if (inFlight.add(target.path)) {
            try {
                executor.execute {
                    try {
                        load(ref.serverId, itemId, dir, target)
                    } finally {
                        inFlight.remove(target.path)
                    }
                }
            } catch (e: Exception) {
                inFlight.remove(target.path) // executor refused (shut down)
            }
        }
        return null
    }

    /** Sign-out: drops every cached server cover of [serverId] (a title's own downloaded cover is not in this cache). Blocking. */
    fun forgetServer(serverId: String) {
        if (!SERVER_ID.matches(serverId)) return
        val dir = File(cacheDir, serverId)
        misses.keys.removeAll { it.startsWith(dir.path + File.separator) }
        dir.deleteRecursively()
    }

    private fun load(serverId: String, itemId: String, dir: File, target: File) {
        val result = try {
            fetch(serverId, itemId)
        } catch (e: Exception) {
            Log.w(TAG, "cover fetch failed: $e")
            Fetch.Failed
        }
        when (result) {
            Fetch.Unavailable -> return
            Fetch.Missing -> { misses[target.path] = clock() + MISS_TTL_MS; return }
            Fetch.Failed -> { misses[target.path] = clock() + ERROR_TTL_MS; return }
            is Fetch.Found -> if (result.bytes.isEmpty()) { misses[target.path] = clock() + MISS_TTL_MS; return }
        }
        try {
            dir.mkdirs()
            val part = File(dir, target.name + ".part")
            part.writeBytes(result.bytes)
            if (!part.renameTo(target)) {
                part.delete()
                return
            }
            // Older versions of the same cover are stale now.
            dir.listFiles { f -> f.name.startsWith("$itemId@") && f.name != target.name }?.forEach { it.delete() }
        } catch (e: IOException) {
            Log.w(TAG, "cover cache write failed", e)
            return
        }
        misses.remove(target.path)
        onFetched?.invoke(serverId, itemId)
    }

    companion object {
        const val DIR = "car_covers"
        /** Parallel cover downloads. */
        const val MAX_FETCHES = 2
        /** A cover larger than this is not cached (and not shown). */
        const val MAX_BYTES = 8 * 1024 * 1024
        /** Requested cover width: enough for Android Auto's tiles and the notification, far smaller than originals. */
        const val WIDTH_PX = 400
        /** A title without a cover on the server is asked for again after this long. */
        const val MISS_TTL_MS = 30 * 60_000L
        /** After a failed request the cover is asked for again after this long. */
        const val ERROR_TTL_MS = 2 * 60_000L
        private const val TAG = "CoverStore"
        private val SERVER_ID = Regex("[0-9a-f]{16}")
    }
}
