package io.wolftown.lauschig.playback

import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A "play" media button (Bluetooth, car, the homescreen widget) may start the service with `startForegroundService`.
 * Android then requires `startForeground` within a deadline (AOSP `ActiveServices`: no new deadline only if the service
 * record is foreground already), else it kills the app with `ForegroundServiceDidNotStartInTimeException`; stopping the
 * service before that crashes it too. Media3 only calls `startForeground` once something plays, so a play key that
 * plays nothing – the resumption fails (offline and not downloaded, nothing to resume) or a headset double tap only
 * skips – needs this guard.
 *
 * [arm] on every play key while nothing plays ([shouldArm]); something playing ([onEngaged]) ends the arming for good
 * (Media3 owns the foreground then, also after a quick pause). Otherwise, at the deadline (the first press counts) or
 * at once when the resumption fails, the guard settles, never by reading Android's foreground type (API 29–33 keep it
 * after `stopForeground`, so it says nothing):
 * - a loaded title whose Media3 notification is posted: go foreground with that very notification (same id, nothing
 *   is swapped or cancelled, the service keeps running) – [Host.adoptMediaNotification];
 * - otherwise go foreground with a placeholder ([Host.showPlaceholder]); if a resumption is still running, keep it and
 *   wait for the answer, at most [capMs]; else (failed, answered without playing, or no resumption involved) leave the
 *   foreground again and stop an empty service ([Host.leavePlaceholder]).
 * Main thread.
 */
class ForegroundStartGuard(
    private val scope: CoroutineScope,
    private val host: Host,
    private val timeoutMs: Long = timeoutFor(Build.VERSION.SDK_INT),
    private val capMs: Long = CAP_MS,
) {
    /** What the guard asks of the playback service. */
    interface Host {
        /** Something plays or buffers to play (Media3 goes foreground itself). */
        fun engaged(): Boolean
        fun hasItems(): Boolean
        /** `startForeground` with Media3's media notification if it is posted right now; true if that happened. */
        fun adoptMediaNotification(): Boolean
        /** `startForeground` with the guard's own placeholder notification. */
        fun showPlaceholder()
        /** Leaves the placeholder's foreground, removes the placeholder and stops the service if the player is empty. */
        fun leavePlaceholder()
    }

    private var job: Job? = null
    private var isArmed = false
    /** null: the resumption has not answered (yet). */
    private var answer: Boolean? = null
    /** The player was empty when armed, so Media3 asks the resumption for a title. */
    private var expectResumption = false
    private var placeholderUp = false

    val armed: Boolean get() = isArmed

    /** A play key while nothing plays; a second press keeps the first deadline (Android counts from the first start). */
    fun arm() {
        if (isArmed) return
        isArmed = true
        answer = null
        placeholderUp = false
        expectResumption = !host.hasItems()
        job = scope.launch {
            delay(timeoutMs)
            job = null
            settle()
        }
    }

    /** Something plays (player listener): Media3 owns the foreground from here; this arming is over for good. */
    fun onEngaged() = disarm()

    /** The playback resumption answered: a success waits for playback (or the deadline), a failure settles at once. */
    fun resumption(ok: Boolean) {
        if (!isArmed) return
        answer = ok
        if (!ok) settle()
    }

    fun release() = disarm()

    private fun settle() {
        if (!isArmed) return
        if (host.engaged()) return disarm()
        // A loaded title keeps its Media3 notification: meet the deadline with it instead of swapping it out.
        if (!placeholderUp && host.hasItems() && host.adoptMediaNotification()) return disarm()
        if (!placeholderUp) {
            host.showPlaceholder()
            placeholderUp = true
        }
        if (expectResumption && answer == null) {
            // A slow resumption (server, details fetch): stay in the foreground meanwhile, but not forever.
            job?.cancel()
            job = scope.launch {
                delay(capMs)
                job = null
                if (!isArmed) return@launch
                if (!host.engaged()) host.leavePlaceholder()
                disarm()
            }
            return
        }
        host.leavePlaceholder()
        disarm()
    }

    private fun disarm() {
        job?.cancel()
        job = null
        isArmed = false
        placeholderUp = false
    }

    companion object {
        /**
         * Android's deadline for `startForeground` after `startForegroundService` is 10 s up to API 33 and longer from 34
         * (30 s seen on the API 35 emulator); keep a margin.
         */
        fun timeoutFor(sdk: Int): Long = if (sdk >= 34) 20_000L else 7_000L

        /** How long a placeholder waits for a slow resumption before it gives up. */
        const val CAP_MS = 60_000L

        /**
         * Media3's media notification id: `DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID` (Media3 1.11.1 posts
         * it with `NotificationManager.notify(id, …)`, no tag; Lauschig keeps the default provider).
         */
        const val MEDIA_NOTIFICATION_ID = 1001

        /** Every play key while nothing plays (a playing service is foreground: Android sets no new deadline then). */
        fun shouldArm(intent: Intent, engaged: Boolean): Boolean = !engaged && isPlayKey(intent)

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
