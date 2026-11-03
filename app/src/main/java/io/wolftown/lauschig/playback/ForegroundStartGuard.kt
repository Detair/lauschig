package io.wolftown.lauschig.playback

import android.content.Intent
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A "play" media button (Bluetooth, car, the homescreen widget) may start the service with `startForegroundService`
 * from a dead process. Android then requires `startForeground` within a few seconds, and Media3 only calls it once
 * something plays. When the resumption fails (offline and not downloaded, the title is gone, nothing to resume),
 * nothing plays and the system kills the app with `ForegroundServiceDidNotStartInTimeException`.
 *
 * [arm] on such an intent; when the resumption reports a failure, or nothing plays after [timeoutMs], [settle] runs
 * (the service goes foreground for a moment and stops, like Media3's own `stopSelfSafely`). Main thread.
 */
class ForegroundStartGuard(
    private val scope: CoroutineScope,
    /** Something plays or is about to (Media3 then goes foreground itself). */
    private val engaged: () -> Boolean,
    private val settle: () -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var job: Job? = null

    val armed: Boolean get() = job?.isActive == true

    fun arm() {
        job?.cancel()
        job = scope.launch {
            delay(timeoutMs)
            job = null
            if (!engaged()) settle()
        }
    }

    /** The playback resumption answered: on success Media3 takes over; a failure settles at once. */
    fun resumption(ok: Boolean) {
        if (!armed) return
        job?.cancel()
        job = null
        if (!ok && !engaged()) settle()
    }

    fun release() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Below Android's 10 s deadline for `startForeground` (API 26–33; newer versions allow more). */
        const val TIMEOUT_MS = 8_000L

        /** A media-button intent that may start playback (the keys Media3's MediaButtonReceiver forwards, and PLAY_PAUSE). */
        fun isPlayKey(intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            return key.action == KeyEvent.ACTION_DOWN && key.keyCode in setOf(
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK,
            )
        }
    }
}
