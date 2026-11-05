package io.wolftown.lauschig.playback

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuietSwitchTest {
    private val applied = mutableListOf<Float>()
    private val volume = PlayerVolume { applied += it }
    private var playing = true
    private val log = mutableListOf<String>()
    private val switch = QuietSwitch(gain = volume::setSwitchGain, playing = { playing }, steps = 4, stepMs = 10, settleMs = 40)

    private fun r(v: Float) = Math.round(v * 100) / 100f

    @Test fun whilePlaying_fadesOut_switchesInSilence_andFadesBackIn() = runTest {
        volume.request(0.8f)
        applied.clear()
        switch.run { log += "switch@${r(applied.last())}" }
        assertEquals(listOf(0.6f, 0.4f, 0.2f, 0f, 0.2f, 0.4f, 0.6f, 0.8f), applied.map(::r))
        assertEquals(listOf("switch@0.0"), log)
        assertTrue("took $currentTime ms", currentTime in 80L..200L)
    }

    @Test fun whilePaused_justSwitches() = runTest {
        playing = false
        switch.run { log += "switch" }
        assertEquals(listOf("switch"), log)
        assertTrue(applied.isEmpty())
        assertEquals(0L, currentTime)
    }

    @Test fun cancelledHalfway_restoresTheVolume_andStillSwitches() = runTest {
        volume.request(0.8f)
        val job = launch { switch.run { log += "switch" } }
        advanceTimeBy(15) // two steps down
        job.cancel()
        job.join()
        assertEquals(0.8f, applied.last(), 0f)
        assertEquals(listOf("switch"), log)
    }

    // Review 1: the sleep timer fades while the compressor is switched – the end volume is the timer's, never a stale one.
    @Test fun aSleepFade_duringTheSwitch_endsAtTheTimersVolume() = runTest {
        volume.request(0.3f) // the sleep fade is under way
        val job = launch { switch.run { log += "switch" } }
        advanceTimeBy(15)
        volume.request(0.2f) // the fade goes on …
        advanceTimeBy(30)
        volume.request(1f) // … the timer ends (or is cancelled) and restores full volume
        advanceUntilIdle()
        job.join()
        assertEquals(1f, applied.last(), 0f)
        assertTrue("never louder than asked: $applied", applied.all { it <= 1f })
    }

    @Test fun aSwitch_duringTheSleepFade_neverRestoresAnOlderVolume() = runTest {
        volume.request(1f)
        val job = launch { switch.run { log += "switch" } }
        advanceTimeBy(15)
        volume.request(0.8f) // the timer's fade begins while the switch fades
        advanceTimeBy(40)
        volume.request(0.6f)
        advanceUntilIdle()
        job.join()
        assertEquals(0.6f, applied.last(), 0f)
        assertEquals(0.6f, volume.requested, 0f)
    }

    @Test fun fades_whileThePlayerWantsToPlay_evenBuffering() {
        assertTrue(QuietSwitch.fades(playWhenReady = true, playbackState = Player.STATE_READY))
        assertTrue(QuietSwitch.fades(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
        assertFalse(QuietSwitch.fades(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(QuietSwitch.fades(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertFalse(QuietSwitch.fades(playWhenReady = true, playbackState = Player.STATE_IDLE))
    }
}
