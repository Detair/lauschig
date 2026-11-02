package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMonitorTest {
    private var now = 1_000_000L
    private val m = StreamMonitor { now }

    private fun tick(ms: Long) { now += ms }

    @Test fun rateWindow_averagesOverWindow_andDropsOldSamples() {
        val w = RateWindow(3_000)
        w.add(0, 0) // burst starts
        w.add(1_000, 150_000)
        w.add(2_000, 150_000)
        w.add(3_000, 150_000)
        assertEquals(150_000L, w.bytesPerSec(3_000)) // 450 KB in 3 s
        assertEquals(100_000L, w.bytesPerSec(4_000)) // the first chunk left the window
        assertEquals(0L, w.bytesPerSec(10_000))
    }

    @Test fun rateWindow_newBurstAfterIdle_measuresFromItsStart() {
        val w = RateWindow(3_000)
        w.add(0, 10)
        w.add(20_000, 100_000)
        assertEquals(100_000L, w.bytesPerSec(21_000))
    }

    @Test fun idle_untilActive() {
        assertFalse(m.state.value.active)
        assertEquals(StreamPhase.IDLE, m.state.value.phase)
        m.update(active = false, playing = false, buffering = true, bufferedAheadMs = 0)
        assertEquals(StreamPhase.IDLE, m.state.value.phase)
    }

    @Test fun phases_startingThenPlayingThenStalled() {
        m.onSeekOrPrepare()
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        assertEquals(StreamPhase.STARTING, m.state.value.phase)
        val startedAt = now
        tick(3_000)
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 1_000)
        assertEquals(startedAt, m.state.value.phaseSinceMs)

        m.update(active = true, playing = true, buffering = false, bufferedAheadMs = 40_000)
        assertEquals(StreamPhase.PLAYING, m.state.value.phase)
        assertEquals(40_000L, m.state.value.bufferedAheadMs)

        tick(1_000)
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        assertEquals(StreamPhase.STALLED, m.state.value.phase)
        assertEquals(now, m.state.value.phaseSinceMs)
    }

    @Test fun seekAfterPlaying_startsAgain() {
        m.update(active = true, playing = true, buffering = false, bufferedAheadMs = 40_000)
        m.onSeekOrPrepare()
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        assertEquals(StreamPhase.STARTING, m.state.value.phase)
    }

    @Test fun readyButNotPlaying_isNotAStall() {
        // e.g. a transient audio-focus loss: playWhenReady stays true, nothing is loading.
        m.update(active = true, playing = true, buffering = false, bufferedAheadMs = 40_000)
        m.update(active = true, playing = false, buffering = false, bufferedAheadMs = 40_000)
        assertEquals(StreamPhase.PLAYING, m.state.value.phase)
    }

    @Test fun bytes_countFromLoadStart_andRateUsesThreeSecondWindow() {
        m.onSeekOrPrepare()
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        m.onTransferStart(position = 0, contentLength = 50_000_000)
        repeat(4) { tick(1_000); m.onBytes(200_000) }
        val s = m.state.value
        assertEquals(800_000L, s.bytes)
        assertEquals(200_000L, s.bytesPerSec)
        assertEquals(200_000L, s.recentBytesPerSec)
        assertNull(s.expectedBytes) // a read from the start: its length is the whole file, not what playback needs
    }

    @Test fun forwardJumpToIndex_givesExpectedBytes_untilTheNextTransfer() {
        m.onSeekOrPrepare()
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        m.onTransferStart(position = 0, contentLength = 400_000_000)
        m.onBytes(1_000)
        // moov at the end of the file: ExoPlayer jumps there and reads the rest of the file.
        m.onTransferStart(position = 399_000_000, contentLength = 1_000_000)
        assertEquals(1_001_000L, m.state.value.expectedBytes)
        tick(1_000); m.onBytes(500_000)
        assertEquals(501_000L, m.state.value.bytes)
        // Back to the first sample: that read is the whole book, no meaningful expectation.
        m.onTransferStart(position = 40, contentLength = 398_999_960)
        assertNull(m.state.value.expectedBytes)
    }

    @Test fun transferAtOffsetWithoutEarlierRead_hasNoExpectation() {
        // A seek: one read from the new position to the end of the file.
        m.onSeekOrPrepare()
        m.onTransferStart(position = 5_000_000, contentLength = 300_000_000)
        assertNull(m.state.value.expectedBytes)
    }

    @Test fun unknownContentLength_hasNoExpectation() {
        m.onSeekOrPrepare()
        m.onTransferStart(position = 0, contentLength = null)
        m.onTransferStart(position = 1_000_000, contentLength = null)
        assertNull(m.state.value.expectedBytes)
    }

    @Test fun seek_resetsLoadBytes_butKeepsConnectionRate() {
        m.onSeekOrPrepare()
        m.onTransferStart(0, null)
        tick(1_000); m.onBytes(300_000)
        m.onSeekOrPrepare()
        assertEquals(0L, m.state.value.bytes)
        assertTrue(m.state.value.recentBytesPerSec > 0)
    }

    @Test fun stall_resetsLoadBytes() {
        m.update(active = true, playing = true, buffering = false, bufferedAheadMs = 10_000)
        m.onTransferStart(0, null)
        m.onBytes(300_000)
        m.update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        assertEquals(0L, m.state.value.bytes)
        tick(300); m.onBytes(1_000)
        assertEquals(1_000L, m.state.value.bytes)
    }

    @Test fun recentRate_usesTenSecondWindow() {
        m.onTransferStart(0, null)
        m.onBytes(1_000_000)
        tick(5_000); m.onBytes(0)
        assertEquals(0L, m.state.value.bytesPerSec)       // nothing in the last 3 s
        assertEquals(200_000L, m.state.value.recentBytesPerSec) // 1 MB over the 5 s since the burst started
    }

    @Test fun bytes_publishedAtMostEvery250ms() {
        m.onTransferStart(0, null)
        m.onBytes(1_000)
        assertEquals(0L, m.state.value.bytes)
        tick(250); m.onBytes(1_000)
        assertEquals(2_000L, m.state.value.bytes)
    }

    @Test fun downloadThrottled_trueWhileAnyDownloadIsThrottled() {
        m.setDownloadThrottled("a", true)
        m.setDownloadThrottled("b", true)
        m.setDownloadThrottled("a", false)
        assertTrue(m.downloadThrottled.value)
        m.setDownloadThrottled("b", false)
        assertFalse(m.downloadThrottled.value)
    }
}
