package io.wolftown.lauschig.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class VolumePlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState(itemId = "b1", isPlaying = true, durationSec = 3600.0))
    val volumes = mutableListOf<Float>()
    var pauses = 0
    var refreshes = 0
    override fun play(serverId: String, itemId: String, startSec: Double?) {}
    override fun togglePlayPause() {}
    override fun pause() { pauses++; state.value = state.value.copy(isPlaying = false) }
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
    override fun setVolume(volume: Float) { volumes += volume }
    override fun refreshNow() { refreshes++ }
}

private class FakeShakes : ShakeSource {
    var listening: (() -> Unit)? = null
    var starts = 0
    override fun start(onShake: () -> Unit) { starts++; listening = onShake }
    override fun stop() { listening = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    private val player = VolumePlayer()
    private val shakes = FakeShakes()
    private var extended = 0
    private var chimes = 0
    private val chimeOn = MutableStateFlow(true)
    // A huge tick: the loop runs once per start; the test drives step() itself.
    private val timer = SleepTimer(
        player, shakes, CoroutineScope(UnconfinedTestDispatcher()), clock = { 0L }, tickMs = Long.MAX_VALUE / 4, onExtended = { extended++ },
        chime = { chimes++ }, chimeEnabled = chimeOn,
    )

    @Test fun chime_soundsOnceThirtySecondsBeforeTheEnd() {
        timer.start(SleepMode.Minutes(1))
        timer.step(29_000)
        assertEquals(0, chimes)
        timer.step(2_000)
        assertEquals(1, chimes)
        timer.step(1_000)
        assertEquals(1, chimes)
    }

    /** The sound is elsewhere, and the keys steer the receiver: no tone at an unknown loudness from the phone. */
    @Test fun chime_silentWhileCasting_theFadeStillRuns() {
        player.state.value = player.state.value.copy(outputName = "Wohnzimmer")
        timer.start(SleepMode.Minutes(1))
        timer.step(31_000)
        assertEquals(0, chimes)
        timer.step(25_000)
        assertEquals(true, player.volumes.any { it < 1f })
        timer.step(10_000)
        assertEquals(1, player.pauses)
        assertEquals(1f, player.volumes.last())
    }

    // ---- A TV that plays while the state cannot show it: it still has a timer.

    private fun playingUnseen(positionSec: Double = 90.0, speed: Float = 1f, ends: List<Double> = listOf(100.0, 3600.0)) {
        player.state.value = player.state.value.copy(
            isPlaying = false, isLoading = true, outputName = "Wohnzimmer", outputActivity = io.wolftown.lauschig.cast.CastActivity.PLAYING_UNSEEN,
            positionSec = positionSec, speed = speed, chapterIndex = 0, chapterEndsSec = ends,
        )
    }

    @Test fun minutes_runOnATvThatPlaysUnseen_andFadeAndPause() {
        playingUnseen()
        timer.start(SleepMode.Minutes(1))
        timer.step(31_000)
        assertEquals(0, player.pauses)
        timer.step(25_000)
        assertTrue(player.volumes.any { it < 1f })
        timer.step(10_000)
        assertEquals(1, player.pauses)
        assertEquals(1f, player.volumes.last())
        assertNull(timer.state.value)
    }

    /** Its position stands, so the chapter's end is counted from the last place known, at the speed it plays. */
    @Test fun endOfChapter_runsOnATvThatPlaysUnseen_fromTheLastKnownPlace() {
        playingUnseen(positionSec = 90.0, speed = 2f)
        timer.start(SleepMode.EndOfChapter)
        timer.step(1_000)
        assertEquals(0, player.pauses)
        timer.step(3_000)
        assertEquals(0, player.pauses)
        assertTrue("the fade is on", player.volumes.any { it < 1f })
        timer.step(1_100)
        assertEquals(1, player.pauses)
    }

    @Test fun endOfChapter_takesARealPositionAsSoonAsThereIsOne() {
        playingUnseen(positionSec = 90.0)
        timer.start(SleepMode.EndOfChapter)
        timer.step(1_000)
        timer.step(5_000)
        // Proven after all, further back than the count had got.
        player.state.value = player.state.value.copy(isPlaying = true, isLoading = false, outputActivity = io.wolftown.lauschig.cast.CastActivity.PLAYING, positionSec = 50.0)
        timer.step(1_000)
        assertEquals(0, player.pauses)
        assertEquals(50_000L, timer.state.value!!.remainingMs)
    }

    /** An ordinary load is what it was: the state is reset and no tick reads it. */
    @Test fun anOrdinaryLoad_stillHoldsTheTimer() {
        playingUnseen()
        player.state.value = player.state.value.copy(outputActivity = io.wolftown.lauschig.cast.CastActivity.PLAYING)
        timer.start(SleepMode.Minutes(1))
        timer.step(120_000)
        assertEquals(0, player.pauses)
    }

    @Test fun chime_silentWhenTurnedOff() {
        chimeOn.value = false
        timer.start(SleepMode.Minutes(1))
        timer.step(31_000)
        assertEquals(0, chimes)
    }

    @Test fun countdown_fade_shake_andEnd() {
        timer.start(SleepMode.Minutes(1))
        assertEquals(60_000L, timer.state.value!!.remainingMs)
        assertFalse(timer.state.value!!.shakeToExtend.not()) // the whole minute is the shake window
        assertEquals(1, shakes.starts)

        timer.step(55_000)
        assertEquals(0.25f, player.volumes.last(), 0.001f)
        assertTrue(player.refreshes > 0)

        shakes.listening!!()
        assertEquals(1, extended)
        assertEquals(1f, player.volumes.last(), 0f)
        assertEquals(65_000L, timer.state.value!!.remainingMs)
        assertNull(shakes.listening) // out of the last minute again

        timer.step(65_000)
        assertEquals(1, player.pauses)
        assertEquals(1f, player.volumes.last(), 0f)
        assertNull(timer.state.value)
    }

    @Test fun cancel_midFade_restoresVolume_andStopsListening() {
        timer.start(SleepMode.Minutes(1))
        timer.step(58_000)
        timer.cancel()
        assertEquals(1f, player.volumes.last(), 0f)
        assertNull(shakes.listening)
        assertNull(timer.state.value)
        assertEquals(0, player.pauses)
    }

    @Test fun longTimer_listensForShakesOnlyInTheLastMinute() {
        timer.start(SleepMode.Minutes(30))
        assertEquals(0, shakes.starts)
        assertFalse(timer.state.value!!.shakeToExtend)
        timer.step(29 * 60_000L + 1_000)
        assertEquals(1, shakes.starts)
        assertTrue(timer.state.value!!.shakeToExtend)
    }

    @Test fun titleSwitch_doesNotFireTheTimer_andChapterModeFollowsTheNewTitle() {
        player.state.value = PlayerUiState(itemId = "b1", isPlaying = true, positionSec = 10.0, durationSec = 300.0, chapterIndex = 0, chapterEndsSec = listOf(100.0, 300.0))
        timer.start(SleepMode.EndOfChapter)
        // play() of another title: the state is reset while it loads (no duration, no chapters).
        player.state.value = PlayerUiState(itemId = "b2", isLoading = true)
        timer.step(250)
        assertEquals(0, player.pauses)
        assertEquals(90_000L, timer.state.value!!.remainingMs) // last valid value kept
        player.state.value = PlayerUiState(itemId = "b2", isPlaying = true, positionSec = 500.0, durationSec = 3600.0, chapterIndex = 1, chapterEndsSec = listOf(400.0, 700.0, 3600.0))
        timer.step(250)
        assertEquals(0, player.pauses)
        assertEquals(200_000L, timer.state.value!!.remainingMs)
    }

    @Test fun minutesMode_continuesAcrossATitleSwitch() {
        timer.start(SleepMode.Minutes(5))
        timer.step(60_000)
        player.state.value = PlayerUiState(itemId = "b2", isLoading = true)
        timer.step(250)
        player.state.value = PlayerUiState(itemId = "b2", isPlaying = true, durationSec = 100.0)
        timer.step(1_000)
        assertEquals(0, player.pauses)
        assertEquals(239_000L, timer.state.value!!.remainingMs)
    }

    @Test fun longPause_cancelsMinutesTimer() {
        timer.start(SleepMode.Minutes(30))
        player.state.value = player.state.value.copy(isPlaying = false)
        timer.step(10 * 60_000L)
        assertNull(timer.state.value)
        assertEquals(0, player.pauses)
    }
}
