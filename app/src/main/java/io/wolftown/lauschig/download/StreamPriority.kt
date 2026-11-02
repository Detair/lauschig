package io.wolftown.lauschig.download

import io.wolftown.lauschig.playback.StreamPhase
import io.wolftown.lauschig.playback.StreamStats

/**
 * Lets a stream win over a download on a weak connection; one instance per running download, asked before every chunk.
 * Throttles while the stream starts, stalls or has less than 30 s buffered, and stays throttled until 60 s are buffered
 * during playback (hysteresis). If the stream still stalls for 5 s (or does not start within 20 s) while throttled, the
 * download pauses. A playing stream whose buffer is complete never throttles. [clock] must be the [StreamStats] clock ([io.wolftown.lauschig.playback.StreamMonitor.now]).
 */
class StreamPriority(private val clock: () -> Long) {
    enum class Decision { FULL, THROTTLE, PAUSE }

    private var throttling = false
    private var throttledSince = 0L

    /** Throttled for 5 s at a stretch; normal stream starts are shorter and do not make the hint flicker. */
    val showThrottled: Boolean get() = throttling && clock() - throttledSince >= HINT_AFTER_MS

    fun decide(s: StreamStats): Decision {
        val now = clock()
        // No stream, or one whose buffer cannot grow any more (last file loaded to its end): nothing to yield for.
        if (!s.active || s.phase == StreamPhase.IDLE || (s.phase == StreamPhase.PLAYING && s.bufferComplete)) {
            throttling = false
            return Decision.FULL
        }
        if (!throttling) {
            if (s.phase == StreamPhase.PLAYING && s.bufferedAheadMs >= THROTTLE_BELOW_MS) return Decision.FULL
            throttling = true
            throttledSince = now
        } else if (s.phase == StreamPhase.PLAYING && s.bufferedAheadMs >= FULL_FROM_MS) {
            throttling = false
            return Decision.FULL
        }
        // Only the part of the stall that happened while throttled counts: the download gets its chance to yield first.
        val stuck = now - maxOf(s.phaseSinceMs, throttledSince)
        return when {
            s.phase == StreamPhase.STALLED && stuck >= STALL_PAUSE_MS -> Decision.PAUSE
            s.phase == StreamPhase.STARTING && stuck >= START_PAUSE_MS -> Decision.PAUSE
            else -> Decision.THROTTLE
        }
    }

    companion object {
        const val THROTTLE_BELOW_MS = 30_000L
        const val FULL_FROM_MS = 60_000L
        const val STALL_PAUSE_MS = 5_000L
        const val START_PAUSE_MS = 20_000L
        const val HINT_AFTER_MS = 5_000L
        /** Pause per 64 KB while throttled. */
        const val THROTTLE_DELAY_MS = 250L
    }
}
