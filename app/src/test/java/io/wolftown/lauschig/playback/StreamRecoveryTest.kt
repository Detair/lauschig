package io.wolftown.lauschig.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** With Robolectric: the recovery writes to the app's log. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StreamRecoveryTest {
    /** A player that stopped on a stream error and still wants to play, until told otherwise. */
    private class FakeHost : StreamRecovery.Host {
        var waits = true
        var title: Any? = "A"
        var prepares = 0
        override fun waitsToStream() = waits
        override fun title() = title
        override fun prepare() { prepares++ }
    }

    private val host = FakeHost()
    private val online = MutableStateFlow(true)

    private fun TestScope.recovery() = StreamRecovery(host, online, backgroundScope, delaysMs = listOf(2_000, 10_000, 30_000))

    @Test fun streamError_withTheServerInReach_preparesAgainAfterAShortWait() = runTest {
        val r = recovery()
        r.onError(retryable = true)
        advanceTimeBy(1_999); runCurrent()
        assertEquals(0, host.prepares)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, host.prepares)
    }

    @Test fun streamError_offline_waitsForTheServer() = runTest {
        online.value = false
        val r = recovery()
        r.onError(retryable = true)
        advanceTimeBy(7 * 60_000L); runCurrent()
        assertEquals(0, host.prepares)
        online.value = true; runCurrent()
        assertEquals(1, host.prepares)
    }

    /** After ten minutes the service is no longer in the foreground, and Android lets nothing start from the background. */
    @Test fun serverBackTooLate_nothingIsStarted() = runTest {
        online.value = false
        val r = recovery()
        r.onError(retryable = true)
        advanceTimeBy(8 * 60_000L); runCurrent()
        online.value = true
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, host.prepares)
    }

    @Test fun cures_isAboutTheSource_andNotAboutAnAnswerThatStaysTheSame() {
        fun http(status: Int) = PlaybackException(
            "http", HttpDataSource.InvalidResponseCodeException(status, null, null, emptyMap(), DataSpec(Uri.parse("https://abs.example/f")), ByteArray(0)),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )
        assertTrue(StreamRecovery.cures(PlaybackException("net", IOException("reset"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertTrue(StreamRecovery.cures(http(502)))
        assertTrue(StreamRecovery.cures(http(429)))
        assertFalse(StreamRecovery.cures(http(404)))
        assertFalse(StreamRecovery.cures(http(403)))
        assertFalse(StreamRecovery.cures(PlaybackException("codec", null, PlaybackException.ERROR_CODE_DECODING_FAILED)))
    }

    @Test fun pausedMeanwhile_nothingIsStarted() = runTest {
        val r = recovery()
        r.onError(retryable = true)
        host.waits = false // the listener paused, or pressed play and it plays again
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, host.prepares)
    }

    @Test fun anotherTitleMeanwhile_nothingIsStarted() = runTest {
        online.value = false
        val r = recovery()
        r.onError(retryable = true)
        host.title = "B"
        online.value = true
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, host.prepares)
    }

    @Test fun errorThatARetryCannotCure_orNothingWaiting_isLeftAlone() = runTest {
        val r = recovery()
        r.onError(retryable = false)
        host.waits = false
        r.onError(retryable = true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, host.prepares)
    }

    @Test fun threeFailedTries_thenItGivesUp_untilTheTitlePlaysAgain() = runTest {
        val r = recovery()
        r.onError(retryable = true)
        advanceTimeBy(2_000); runCurrent()
        r.onError(retryable = true) // the try failed
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, host.prepares)
        advanceTimeBy(1); runCurrent()
        r.onError(retryable = true)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, host.prepares)
        r.onError(retryable = true)
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(3, host.prepares)

        // Someone pressed play and it plays: the next dropout gets its tries again.
        r.onPlaying()
        r.onError(retryable = true)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(4, host.prepares)
    }

    @Test fun aSecondErrorWhileATryWaits_doesNotStartASecondOne() = runTest {
        val r = recovery()
        r.onError(retryable = true)
        r.onError(retryable = true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, host.prepares)
    }
}
