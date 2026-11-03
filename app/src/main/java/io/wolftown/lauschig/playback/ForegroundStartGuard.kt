package io.wolftown.lauschig.playback

import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A "play" media button (Bluetooth, car, the homescreen widget) may start the service with `startForegroundService`
 * from a dead process. Android then requires `startForeground` within a deadline, and Media3 only calls it once
 * something plays. When the resumption fails (offline and not downloaded, the title is gone, nothing to resume),
 * nothing plays and the system kills the app with `ForegroundServiceDidNotStartInTimeException`.
 *
 * [arm] on such an intent (only with an empty player, see [shouldArm]). Then:
 * - something plays ([onEngaged]): Media3 owns the foreground, the guard lets go for good;
 * - the resumption fails: [settle] with stop = true (go foreground for a moment, stop the service), at once;
 * - the deadline ([timeoutMs], the first press counts) passes while the resumption is still running: [settle] with
 *   stop = false (in the foreground with a placeholder, the service keeps running) and wait for the answer – a failure
 *   then settles with stop = true, a success hands over to Media3;
 * - the deadline passes after a successful resumption but nothing plays: [settle] with stop = true.
 * Main thread.
 */
class ForegroundStartGuard(
    private val scope: CoroutineScope,
    /** Something plays or is about to (Media3 then goes foreground itself). */
    private val engaged: () -> Boolean,
    private val settle: (stop: Boolean) -> Unit,
    private val timeoutMs: Long = timeoutFor(Build.VERSION.SDK_INT),
) {
    private var job: Job? = null
    private var isArmed = false
    /** null: the resumption has not answered yet. */
    private var answer: Boolean? = null

    val armed: Boolean get() = isArmed

    /** A play key on an empty player; a second press keeps the first deadline (Android counts from the first start). */
    fun arm() {
        if (isArmed) return
        isArmed = true
        answer = null
        job = scope.launch {
            delay(timeoutMs)
            job = null
            deadline()
        }
    }

    private fun deadline() {
        if (!isArmed) return
        when {
            engaged() -> disarm()
            answer == null -> settle(false) // keep waiting for the answer, in the foreground
            else -> {
                disarm()
                settle(true)
            }
        }
    }

    /** Something plays (player listener): Media3 owns the foreground from here; this arming is over for good. */
    fun onEngaged() = disarm()

    /** The playback resumption answered: a success waits for playback (or the deadline), a failure settles at once. */
    fun resumption(ok: Boolean) {
        if (!isArmed) return
        answer = ok
        if (ok) return
        val playing = engaged()
        disarm()
        if (!playing) settle(true)
    }

    fun release() = disarm()

    private fun disarm() {
        job?.cancel()
        job = null
        isArmed = false
    }

    companion object {
        /**
         * Android's deadline for `startForeground` after `startForegroundService` is 10 s up to API 33 and longer from 34
         * (30 s seen on the API 35 emulator); keep a margin.
         */
        fun timeoutFor(sdk: Int): Long = if (sdk >= 34) 20_000L else 7_000L

        /** The deadline on this device. */
        val TIMEOUT_MS: Long get() = timeoutFor(Build.VERSION.SDK_INT)

        /**
         * Arm only for a play key while the player holds nothing: with a loaded (paused) title PLAY just plays it and
         * Media3 goes foreground itself – settling there would take over its notification.
         */
        fun shouldArm(intent: Intent, mediaItemCount: Int): Boolean = mediaItemCount == 0 && isPlayKey(intent)

        /**
         * Settling calls `startForeground` with its own notification id, which makes Android cancel the notification of
         * whoever is in the foreground now (Media3's media notification). On API 29+ a service already in the foreground
         * ([foregroundServiceType] != 0) is left alone unless that is the guard's [ownPlaceholder]; API 28 cannot tell.
         */
        fun maySettle(sdk: Int, foregroundServiceType: Int, ownPlaceholder: Boolean): Boolean =
            ownPlaceholder || sdk < 29 || foregroundServiceType == 0

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
