package io.wolftown.lauschig.playback

import android.media.AudioDeviceInfo

/**
 * Decides whether a newly connected audio output (car, Bluetooth or wired headphones) resumes the last title:
 * only when enabled, nothing plays, and the last title was paused less than [maxPauseMs] ago. Outputs that were
 * already there when watching started are not "new"; several profiles of one device connecting at once
 * (A2DP + LE audio) trigger once ([debounceMs]).
 */
class AutoResumePolicy(
    private val maxPauseMs: Long = MAX_PAUSE_MS,
    private val debounceMs: Long = 10_000,
) {
    private val known = HashSet<Int>()
    private var lastTrigger = Long.MIN_VALUE

    /** Devices connected when the watcher was registered (id to type). */
    fun initialDevices(devices: List<Pair<Int, Int>>) {
        known.clear()
        devices.forEach { known += it.first }
    }

    fun onDevicesRemoved(ids: List<Int>) {
        known.removeAll(ids.toSet())
    }

    /** [devices]: id to [AudioDeviceInfo] type. True: resume now. */
    fun onDevicesAdded(
        devices: List<Pair<Int, Int>>,
        enabled: Boolean,
        isPlayingOrLoading: Boolean,
        lastPausedAt: Long?,
        now: Long,
    ): Boolean {
        val fresh = devices.filter { known.add(it.first) }
        if (fresh.none { isResumeOutput(it.second) }) return false
        if (!enabled || isPlayingOrLoading || lastPausedAt == null) return false
        val since = now - lastPausedAt
        if (since < 0 || since > maxPauseMs) return false
        if (lastTrigger != Long.MIN_VALUE && now - lastTrigger < debounceMs) return false
        lastTrigger = now
        return true
    }

    companion object {
        const val MAX_PAUSE_MS = 2 * 60 * 60_000L

        // Constants are inlined; the BLE types only ever show up on Android 12+.
        @Suppress("InlinedApi")
        private val RESUME_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUS, // car audio (Android Automotive / some head units)
        )

        fun isResumeOutput(type: Int) = type in RESUME_TYPES
    }
}
