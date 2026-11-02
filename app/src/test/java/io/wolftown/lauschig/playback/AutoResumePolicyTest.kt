package io.wolftown.lauschig.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumePolicyTest {
    private val h = 3_600_000L
    private val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private fun policy() = AutoResumePolicy().also { it.initialDevices(listOf(1 to speaker)) }
    private fun ok(p: AutoResumePolicy, now: Long = 10 * h, id: Int = 2, type: Int = a2dp, pausedAt: Long? = now - h, playing: Boolean = false, enabled: Boolean = true) =
        p.onDevicesAdded(listOf(id to type), enabled = enabled, isPlayingOrLoading = playing, lastPausedAt = pausedAt, now = now)

    @Test fun newCarOrHeadset_recentPause_resumes() {
        assertTrue(ok(policy()))
        assertTrue(ok(policy(), type = AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(ok(policy(), type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertTrue(ok(policy(), type = AudioDeviceInfo.TYPE_BUS))
    }

    @Test fun off_playing_noTitle_orTooOld_doNotResume() {
        assertFalse(ok(policy(), enabled = false))
        assertFalse(ok(policy(), playing = true))
        assertFalse(ok(policy(), pausedAt = null))
        assertFalse(ok(policy(), now = 10 * h, pausedAt = 8 * h - 1))
        assertFalse(ok(policy(), now = 10 * h, pausedAt = 10 * h + 60_000)) // clock went backwards: unknown
    }

    @Test fun irrelevantDevices_andDevicesKnownAtRegistration_areIgnored() {
        assertFalse(ok(policy(), type = speaker))
        assertFalse(ok(policy(), type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) // calls
        assertFalse(ok(policy(), id = 1)) // already there when the watcher started
        val p = AutoResumePolicy().also { it.initialDevices(listOf(5 to a2dp)) }
        assertFalse(ok(p, id = 5))
    }

    @Test fun reconnect_afterRemoval_countsAgain_butBurstsAreDebounced() {
        val p = policy()
        assertTrue(ok(p, now = 10 * h))
        assertFalse(ok(p, now = 10 * h + 1_000, id = 3, type = AudioDeviceInfo.TYPE_BLE_HEADSET)) // same car, second profile
        p.onDevicesRemoved(listOf(2))
        assertTrue(ok(p, now = 10 * h + 60_000))
    }
}
