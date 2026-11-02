package io.wolftown.lauschig.download

import io.wolftown.lauschig.download.StreamPriority.Decision.FULL
import io.wolftown.lauschig.download.StreamPriority.Decision.PAUSE
import io.wolftown.lauschig.download.StreamPriority.Decision.THROTTLE
import io.wolftown.lauschig.playback.StreamPhase
import io.wolftown.lauschig.playback.StreamStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPriorityTest {
    private var now = 100_000L
    private val p = StreamPriority { now }

    private fun stats(phase: StreamPhase, aheadSec: Int = 0, phaseSince: Long = now) =
        StreamStats(active = true, phase = phase, phaseSinceMs = phaseSince, bufferedAheadMs = aheadSec * 1_000L)

    @Test fun noStream_full() {
        assertEquals(FULL, p.decide(StreamStats()))
        assertFalse(p.showThrottled)
    }

    @Test fun playingWithEnoughBuffer_full() {
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 45)))
    }

    @Test fun lowBuffer_throttles_untilSixtySecondsAhead() {
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.PLAYING, aheadSec = 20)))
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.PLAYING, aheadSec = 45))) // hysteresis
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.PLAYING, aheadSec = 59)))
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 60)))
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 45)))
    }

    @Test fun startingAndStalled_throttleEvenWithBuffer() {
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STARTING, aheadSec = 100)))
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STALLED, aheadSec = 100)))
        // Back to full only once playing with a full minute ahead.
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 100)))
    }

    @Test fun startingTwentySeconds_pauses() {
        val since = now
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STARTING, phaseSince = since)))
        now += 19_999
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STARTING, phaseSince = since)))
        now += 1
        assertEquals(PAUSE, p.decide(stats(StreamPhase.STARTING, phaseSince = since)))
    }

    @Test fun stalledFiveSeconds_pauses() {
        val since = now
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
        now += 4_999
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
        now += 1
        assertEquals(PAUSE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
    }

    @Test fun stallBeforeThrottling_countsOnlyWhileThrottled() {
        // The download started into a stall that already lasted a while: it gets its 5 s of throttling first.
        val since = now - 30_000
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
        now += 4_000
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
        now += 1_000
        assertEquals(PAUSE, p.decide(stats(StreamPhase.STALLED, phaseSince = since)))
    }

    @Test fun throttledHint_onlyAfterFiveSecondsAtAStretch() {
        p.decide(stats(StreamPhase.STARTING))
        now += 4_999
        p.decide(stats(StreamPhase.PLAYING, aheadSec = 10))
        assertFalse(p.showThrottled)
        now += 1
        assertTrue(p.showThrottled)
        p.decide(stats(StreamPhase.PLAYING, aheadSec = 60))
        assertFalse(p.showThrottled)
        // A new stretch starts from zero.
        p.decide(stats(StreamPhase.PLAYING, aheadSec = 10))
        now += 3_000
        assertFalse(p.showThrottled)
    }

    @Test fun streamEnds_backToFull() {
        p.decide(stats(StreamPhase.STALLED))
        now += 6_000
        assertEquals(FULL, p.decide(StreamStats(active = false)))
        assertFalse(p.showThrottled)
    }

    @Test fun bufferThatCannotGrow_full() {
        // The last file is completely buffered: waiting for 60 s ahead would throttle until the end.
        assertEquals(THROTTLE, p.decide(stats(StreamPhase.PLAYING, aheadSec = 10)))
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 10).copy(bufferComplete = true)))
        assertFalse(p.showThrottled)
        assertEquals(FULL, p.decide(stats(StreamPhase.PLAYING, aheadSec = 5).copy(bufferComplete = true)))
    }
}
