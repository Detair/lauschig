package io.wolftown.lauschig.widget

import io.wolftown.lauschig.auto.CoverRef
import io.wolftown.lauschig.playback.PlayableKey

/**
 * The widget's decoded cover, kept for its [CoverRef]: the 30 s position updates redraw the widget without decoding the
 * file again. A miss (no cover yet) is asked again after [missRetryMs], or at once when that cover [arrived].
 */
class WidgetCoverCache<T : Any>(
    private val missRetryMs: Long = MISS_RETRY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var ref: CoverRef? = null
    private var value: T? = null
    private var loadedAt = 0L
    private var stale = false

    /** The cover of [ref] (null: none), loaded with [load] only when it is not cached. */
    @Synchronized
    fun get(ref: CoverRef?, load: (CoverRef) -> T?): T? {
        if (ref == null) {
            this.ref = null
            value = null
            return null
        }
        val now = clock()
        if (ref == this.ref && !stale && (value != null || now - loadedAt < missRetryMs)) return value
        val v = load(ref)
        this.ref = ref
        value = v
        loadedAt = now
        stale = false
        return v
    }

    /**
     * A cover was fetched in the background. True (and the next [get] loads it again) only if it is the cover the
     * widget shows: an episode's cover is its podcast's, so the library item id decides.
     */
    @Synchronized
    fun arrived(serverId: String, libraryItemId: String): Boolean {
        val r = ref ?: return false
        if (r.serverId != serverId || PlayableKey.parse(r.key).libraryItemId != libraryItemId) return false
        stale = true
        return true
    }

    companion object {
        const val MISS_RETRY_MS = 5 * 60_000L
    }
}
