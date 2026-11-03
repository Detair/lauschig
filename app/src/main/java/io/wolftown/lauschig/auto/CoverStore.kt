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
 * often it is asked). [request] instead waits for that fetch: the provider hands the car a pipe and fills it once the
 * cover is here, so the car's lists need no reload. [onFetched] tells the service about every arrival, and whether a
 * request got it ("delivered"); one nobody received means the car must be made to ask again.
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

    /** What [request] can say at once. */
    sealed interface Answer {
        class Here(val file: File) : Answer
        /** Nothing will come now: a device title without a cover, or one the server did not deliver a moment ago. */
        data object None : Answer
        /** Being fetched: the request's `deliver` is called once, with the file or null, unless it gave up ([cancel]). */
        class Later(val cancel: () -> Unit) : Answer
    }

    /**
     * A cover was fetched and cached (called on the [executor] thread). [delivered]: requests were waiting for it, each
     * took it, and none gave up on it before – nobody who asked is left with a failed load.
     */
    @Volatile var onFetched: ((serverId: String, libraryItemId: String, delivered: Boolean) -> Unit)? = null

    /** Guards [waiting] and every change of [inFlight] that [request] must see together with it. */
    private val lock = Any()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Requests waiting for a cover being fetched, by its cache file. */
    private val waiting = HashMap<String, Waiting>()

    private class Waiting {
        val waiters = mutableListOf<Waiter>()
        /** A request gave up on this cover before it came (its reader got nothing). */
        var gaveUp = false
    }

    /** [deliver]: false if the request no longer takes the answer (it gave up at the same moment). */
    private class Waiter(val deliver: (File?) -> Boolean)
    /** Covers the server did not deliver, until when not to ask again (SystemUI reloads artwork on every change). */
    private val misses = ConcurrentHashMap<String, Long>()

    /** The cover file now, or null (and, for a server title, a fetch in the background). Never blocks on the network. */
    fun file(ref: CoverRef): File? {
        local(ref)?.let { return it }
        if (DeviceLibrary.isLocal(ref.serverId, ref.key)) return null
        val target = cached(ref)
        if (target.isUsable()) return target
        val itemId = PlayableKey.parse(ref.key).libraryItemId
        val dir = target.parentFile!!
        misses[target.path]?.let { until -> if (clock() < until) return null }
        if (inFlight.add(target.path)) {
            try {
                executor.execute {
                    var cached: File? = null
                    try {
                        cached = load(ref.serverId, itemId, dir, target)
                    } finally {
                        val delivered = finish(target.path, cached)
                        if (cached != null) onFetched?.invoke(ref.serverId, itemId, delivered)
                    }
                }
            } catch (e: Exception) {
                finish(target.path, null) // executor refused (shut down)
            }
        }
        return null
    }

    /**
     * Like [file], but a cover being fetched is [Answer.Later] delivered to [deliver] (on the [executor] thread): the
     * file, or null if none came (no cover, failure, offline). [deliver] returns whether it took the answer.
     */
    fun request(ref: CoverRef, deliver: (File?) -> Boolean): Answer {
        file(ref)?.let { return Answer.Here(it) } // a miss starts the fetch
        if (DeviceLibrary.isLocal(ref.serverId, ref.key)) return Answer.None
        val target = cached(ref)
        val waiter = Waiter(deliver)
        synchronized(lock) {
            if (target.path in inFlight) {
                waiting.getOrPut(target.path) { Waiting() }.waiters += waiter
                return Answer.Later {
                    synchronized(lock) { waiting[target.path]?.let { w -> if (w.waiters.remove(waiter)) w.gaveUp = true } }
                }
            }
        }
        // Not being fetched: the server did not deliver it a moment ago, or it arrived just now.
        return target.takeIf { it.isUsable() }?.let { Answer.Here(it) } ?: Answer.None
    }

    /** Whether a fetch of [ref]'s cover is running (it was asked for and may still come). */
    fun pending(ref: CoverRef): Boolean = !DeviceLibrary.isLocal(ref.serverId, ref.key) && cached(ref).path in inFlight

    /** The fetch for [path] ended with [file] (null: none): every waiting request is answered; see [onFetched]. */
    private fun finish(path: String, file: File?): Boolean {
        val w = synchronized(lock) {
            inFlight.remove(path)
            waiting.remove(path)
        } ?: return false
        var allTook = true
        w.waiters.forEach { waiter ->
            val took = try {
                waiter.deliver(file)
            } catch (e: Exception) {
                Log.w(TAG, "a cover request could not take its answer", e)
                false
            }
            if (!took) allTook = false
        }
        return file != null && w.waiters.isNotEmpty() && allTook && !w.gaveUp
    }

    /** Whether [file] would answer with a file right now; never fetches (the car's lists are built with it). */
    fun present(ref: CoverRef): Boolean =
        local(ref) != null || (!DeviceLibrary.isLocal(ref.serverId, ref.key) && cached(ref).isUsable())

    private fun local(ref: CoverRef): File? = localCover(ref.serverId, ref.key)?.takeIf { it.isUsable() }

    /** Episodes show their podcast's cover; one cached file per podcast and cover version ("@" never occurs in an id). */
    private fun cached(ref: CoverRef): File =
        File(File(cacheDir, ref.serverId), "${PlayableKey.parse(ref.key).libraryItemId}@${ref.version}.jpg")

    private fun File.isUsable() = isFile && length() > 0

    /** Sign-out: drops every cached server cover of [serverId] (a title's own downloaded cover is not in this cache). Blocking. */
    fun forgetServer(serverId: String) {
        if (!SERVER_ID.matches(serverId)) return
        val dir = File(cacheDir, serverId)
        misses.keys.removeAll { it.startsWith(dir.path + File.separator) }
        dir.deleteRecursively()
    }

    /** Fetches and caches the cover; the cached [target], or null if none came. */
    private fun load(serverId: String, itemId: String, dir: File, target: File): File? {
        val result = try {
            fetch(serverId, itemId)
        } catch (e: Exception) {
            Log.w(TAG, "cover fetch failed: $e")
            Fetch.Failed
        }
        when (result) {
            Fetch.Unavailable -> return null
            Fetch.Missing -> { misses[target.path] = clock() + MISS_TTL_MS; return null }
            Fetch.Failed -> { misses[target.path] = clock() + ERROR_TTL_MS; return null }
            is Fetch.Found -> if (result.bytes.isEmpty()) { misses[target.path] = clock() + MISS_TTL_MS; return null }
        }
        try {
            dir.mkdirs()
            val part = File(dir, target.name + ".part")
            part.writeBytes(result.bytes)
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
            // Older versions of the same cover are stale now.
            dir.listFiles { f -> f.name.startsWith("$itemId@") && f.name != target.name }?.forEach { it.delete() }
        } catch (e: IOException) {
            Log.w(TAG, "cover cache write failed", e)
            return null
        }
        misses.remove(target.path)
        return target
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
