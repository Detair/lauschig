package io.wolftown.lauschig.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuietSwitchTest {
    private var volume = 0.8f
    private val log = mutableListOf<String>()
    private var playing = true
    private val switch = QuietSwitch(volume = { volume }, setVolume = { volume = it; log += "v$it" }, playing = { playing }, steps = 4, stepMs = 10, settleMs = 40)

    @Test fun whilePlaying_fadesOut_switchesInSilence_andFadesBackToTheVolumeBefore() = runTest {
        switch.run { log += "switch@$volume" }
        assertEquals(listOf("v0.6", "v0.4", "v0.2", "v0.0", "switch@0.0", "v0.2", "v0.4", "v0.6", "v0.8"), log.map { it.replace(Regex("(\\d\\.\\d)\\d+"), "$1") })
        assertEquals(0.8f, volume, 0f)
        assertTrue("took ${currentTime} ms", currentTime in 80L..200L)
    }

    @Test fun whilePaused_justSwitches() = runTest {
        playing = false
        switch.run { log += "switch" }
        assertEquals(listOf("switch"), log)
        assertEquals(0L, currentTime)
    }

    @Test fun silentAlready_switchesWithoutTouchingTheVolume() = runTest {
        volume = 0f
        switch.run { log += "switch" }
        assertEquals(listOf("switch"), log)
    }

    @Test fun cancelledHalfway_restoresTheVolume_andStillSwitches() = runTest {
        val job = launch { switch.run { log += "switch" } }
        advanceTimeBy(15) // two steps down
        job.cancel()
        job.join()
        assertEquals(0.8f, volume, 0f)
        assertTrue(log.contains("switch"))
    }
}
