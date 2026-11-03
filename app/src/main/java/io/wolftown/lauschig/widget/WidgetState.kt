package io.wolftown.lauschig.widget

import io.wolftown.lauschig.auto.CoverRef

/**
 * What the homescreen widget shows: the title in the player (or the last one, paused), written by the playback
 * service. Positions are book-wide (across all files of the title).
 */
data class WidgetState(
    val serverId: String? = null,
    val key: String? = null,
    val title: String = "",
    /** The chapter while playing (the service writes it as the item's subtitle), else the author. */
    val subtitle: String? = null,
    val coverVersion: Long = 0,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val hasTitle: Boolean get() = serverId != null && key != null

    /** 0..1000 for the thin progress bar. */
    val progressPermille: Int get() = if (durationMs <= 0) 0 else (positionMs * 1000 / durationMs).toInt().coerceIn(0, 1000)

    fun coverRef(): CoverRef? = if (serverId != null && key != null) CoverRef(serverId, key, coverVersion) else null

    /** Everything except the position: a change here is pushed at once. */
    fun sameExceptPosition(other: WidgetState): Boolean = copy(positionMs = 0) == other.copy(positionMs = 0)
}

/** The player as the widget needs it; read on the player's thread, mapped without touching the player. */
data class WidgetPlayerSnapshot(
    val serverId: String?,
    val key: String?,
    val title: String?,
    val subtitle: String?,
    val artist: String?,
    val coverVersion: Long,
    val playWhenReady: Boolean,
    /** `Player.STATE_*`. */
    val playbackState: Int,
    val fileIndex: Int,
    val positionInFileMs: Long,
    /** Length of every file of the title in order; 0 when unknown. */
    val fileDurationsMs: List<Long>,
    /** The current file's length as the player knows it (C.TIME_UNSET = negative), preferred over its extra. */
    val currentFileDurationMs: Long,
)

object WidgetMapping {
    private const val STATE_BUFFERING = 2 // Player.STATE_BUFFERING
    private const val STATE_READY = 3 // Player.STATE_READY

    /**
     * What the widget shows of the [stored] state: "playing" only while the playback service runs in this process – after
     * a process death, a reboot or an app update the store can still say "playing" (pause would then do nothing).
     */
    fun forDisplay(stored: WidgetState?, serviceRunning: Boolean): WidgetState? =
        stored?.let { if (it.playing && !serviceRunning) it.copy(playing = false) else it }

    /**
     * The widget state for [s]. An empty player keeps [previous]'s title, paused (the title can be resumed from the
     * widget); with no previous title the widget is empty. "Playing" is the user's intent (play/pause button), like
     * the notification: buffering counts, an ended or failed title does not.
     */
    fun map(s: WidgetPlayerSnapshot, previous: WidgetState?): WidgetState {
        if (s.serverId == null || s.key == null) return previous?.copy(playing = false) ?: WidgetState()
        val durations = s.fileDurationsMs.mapIndexed { i, d ->
            if (i == s.fileIndex && s.currentFileDurationMs > 0) s.currentFileDurationMs else d.coerceAtLeast(0)
        }
        val before = durations.take(s.fileIndex.coerceIn(0, durations.size)).sum()
        val total = durations.sum()
        val position = (before + s.positionInFileMs.coerceAtLeast(0)).let { if (total > 0) it.coerceAtMost(total) else it }
        return WidgetState(
            serverId = s.serverId,
            key = s.key,
            title = s.title.orEmpty(),
            subtitle = s.subtitle?.takeIf { it.isNotBlank() } ?: s.artist?.takeIf { it.isNotBlank() },
            coverVersion = s.coverVersion,
            playing = s.playWhenReady && (s.playbackState == STATE_READY || s.playbackState == STATE_BUFFERING),
            positionMs = position,
            durationMs = total,
        )
    }
}

/**
 * Keeps widget updates rare: every change except the position goes out at once; while playing the position at most
 * every [intervalMs] (the launcher redraws the whole widget per update), while paused at once (a seek).
 */
class WidgetThrottle(private val intervalMs: Long = INTERVAL_MS) {
    private var last: WidgetState? = null
    private var lastAt = 0L

    /** True if [next] should be written now; it then becomes the reference for the next call. */
    fun offer(next: WidgetState, now: Long): Boolean {
        val prev = last
        val push = when {
            prev == null -> true
            !next.sameExceptPosition(prev) -> true
            next.positionMs == prev.positionMs -> false
            !next.playing -> true
            else -> now - lastAt >= intervalMs
        }
        if (push) {
            last = next
            lastAt = now
        }
        return push
    }

    companion object {
        const val INTERVAL_MS = 30_000L
    }
}
