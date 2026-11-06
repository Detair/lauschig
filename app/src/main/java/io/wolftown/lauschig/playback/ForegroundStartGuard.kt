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
 * plays nothing – the resumption fails (offline and not downloaded, nothing to resume), or two quick presses play and
 * pause at once (on this route Media3 sees no double tap) – needs this guard.
 *
 * [arm] on every play key while nothing plays ([shouldArm]); something playing ([onEngaged]) ends the arming for good
 * (Media3 owns the foreground then, also after a quick pause). Otherwise, at the deadline (the first press counts) or
 * at once when the last resumption fails, the guard settles, never by reading Android's foreground type (API 29–33 keep it
 * after `stopForeground`, so it says nothing):
 * - a loaded title whose Media3 notification is posted: go foreground with that very notification (same id, nothing
 *   is swapped or cancelled, the service keeps running) – [Host.adoptMediaNotification];
 * - otherwise go foreground with a placeholder ([Host.showPlaceholder]); if a resumption is still running, keep it and
 *   wait for the answers, at most [capMs]; else (all failed, answered without playing, or no resumption involved) leave
 *   the foreground again and stop an empty service ([Host.leavePlaceholder]).
 *
 * From API 35 the same step comes first, at [arm], before Media3 handles the key ([foregroundFirstFor]); what is left for
 * the deadline is leaving a placeholder if nothing plays. While armed, Media3 must not take that foreground away again
 * ([holdsNotificationUpdate]), and every further key asserts it again. After an arming Media3 is in charge, but for
 * one check at the deadline ([tidyLater]).
 * Main thread.
 */
class ForegroundStartGuard(
    private val scope: CoroutineScope,
    private val host: Host,
    private val timeoutMs: Long = timeoutFor(Build.VERSION.SDK_INT),
    private val capMs: Long = CAP_MS,
    private val foregroundFirst: Boolean = foregroundFirstFor(Build.VERSION.SDK_INT),
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
        /** Removes the placeholder if it is still posted; Android ignores this while it is the foreground notification. */
        fun cancelPlaceholder()
        /** Lets Media3 bring its notification and the foreground up to date. */
        fun updateNotification()
    }

    private var job: Job? = null
    private var isArmed = false
    /** Play keys that found the player empty, so Media3 asked the resumption for a title, and have no answer yet. */
    private var openResumptions = 0
    /** A resumption answered with a title: what is left is waiting for it to play. */
    private var resumed = false
    private var placeholderUp = false
    /** Media3 wanted to update its notification while armed ([holdsNotificationUpdate]). */
    private var updateHeld = false
    private var tidyJob: Job? = null
    /** An arming ended with its placeholder up, and Media3 has yet to replace it ([tidyLater]). */
    private var placeholderLeft = false

    val armed: Boolean get() = isArmed

    /**
     * `onStartCommand`: the guard sees a play key before Media3 does ([next] hands it on). From API 35 this order is what
     * lets the playback start: Media3 asks for audio focus when it handles the key.
     */
    fun onStart(intent: Intent?, engaged: Boolean, next: () -> Int): Int {
        if (intent != null && shouldArm(intent, engaged)) arm()
        return next()
    }

    /**
     * Media3 wants to update its notification without being in the foreground: with an empty player it would call
     * `stopForeground(true)`, with a loaded one `stopForeground(false)`, also before `play()` (a changed speed or skip
     * step updates the buttons on the way). True while that would end the guard's foreground: Media3 must skip this
     * update, and gets another one when the arming ends.
     */
    fun holdsNotificationUpdate(): Boolean {
        if (!foregroundFirst || !isArmed) return false
        updateHeld = true
        return true
    }

    /**
     * A play key while nothing plays. A further press keeps the guard's first deadline. From API 35 it also goes
     * foreground again: if the service has left the foreground since, this start set a new deadline at Android, and
     * stopping the service with that one open would crash the app. The same notification id changes nothing.
     */
    fun arm() {
        if (isArmed) {
            if (!host.hasItems()) openResumptions++
            if (foregroundFirst) {
                val placeholder = placeholderUp
                placeholderUp = false
                if (goForeground()) adopted(placeholder)
            }
            return
        }
        isArmed = true
        resumed = false
        placeholderUp = false
        openResumptions = if (host.hasItems()) 0 else 1
        if (foregroundFirst && goForeground()) return adopted(placeholder = false)
        job = scope.launch {
            delay(timeoutMs)
            job = null
            settle()
        }
    }

    /**
     * Something plays (player listener): Media3 owns the foreground from here; this arming is over for good. A held
     * update does not run now: Media3's controller may not know the title yet and would end the foreground just after
     * `play()`. Its own update follows with the player's change.
     */
    fun onEngaged() {
        val placeholder = isArmed && placeholderUp
        disarm()
        if (foregroundFirst && placeholder) tidyLater(placeholder = true)
    }

    /**
     * A playback resumption answered. A success waits for playback (or the deadline). A failure settles once the last open
     * one has answered, also if another press is still waiting for its title: stopping the service now would destroy
     * that title on arrival. A key that never gets an answer leaves the end to the deadline and the cap.
     * True if this failure ended the arming, so the caller says why.
     */
    fun resumption(ok: Boolean): Boolean {
        if (!isArmed) return false
        if (openResumptions > 0) openResumptions--
        if (ok) resumed = true
        if (resumed || openResumptions > 0) return false
        settle()
        return true
    }

    fun release() {
        disarm()
        tidyJob?.cancel()
        tidyJob = null
        placeholderLeft = false
    }

    private fun settle() {
        if (!isArmed) return
        if (host.engaged()) return onEngaged()
        if (goForeground()) return adopted(placeholder = false)
        if (openResumptions > 0 && !resumed) {
            // A slow resumption (server, details fetch): stay in the foreground meanwhile, but not forever.
            job?.cancel()
            job = scope.launch {
                delay(capMs)
                job = null
                if (!isArmed) return@launch
                if (host.engaged()) return@launch onEngaged()
                endWithoutPlaying()
            }
            return
        }
        endWithoutPlaying()
    }

    private fun endWithoutPlaying() {
        host.leavePlaceholder()
        val held = updateHeld
        disarm()
        if (held) host.updateNotification()
    }

    /** Media3's notification carries the foreground now, which ends the arming. [placeholder]: one was up until now. */
    private fun adopted(placeholder: Boolean) {
        disarm()
        if (foregroundFirst) tidyLater(placeholder)
    }

    /**
     * What Media3 does not do by itself if the player does not change after all (a key it ignores; play and pause within
     * milliseconds): at the deadline, if nothing plays, it gets an update, which ends the foreground; a placeholder it
     * detached is cancelled. Skipped while a new arming runs: that one's end takes over.
     */
    private fun tidyLater(placeholder: Boolean) {
        placeholderLeft = placeholderLeft || placeholder
        tidyJob?.cancel()
        tidyJob = scope.launch {
            delay(timeoutMs)
            tidyJob = null
            if (isArmed) return@launch
            if (!host.engaged()) {
                host.updateNotification()
                if (placeholderLeft) delay(UPDATE_SETTLE_MS) // Media3 builds its notification first, then detaches.
            }
            if (placeholderLeft) {
                placeholderLeft = false
                host.cancelPlaceholder()
            }
        }
    }

    /** `startForeground` now; true if that was with Media3's notification, which ends the arming. */
    private fun goForeground(): Boolean {
        // A loaded title keeps its Media3 notification: go foreground with it instead of swapping it out.
        if (!placeholderUp && host.hasItems() && host.adoptMediaNotification()) return true
        if (!placeholderUp) {
            host.showPlaceholder()
            placeholderUp = true
        }
        return false
    }

    private fun disarm() {
        job?.cancel()
        job = null
        isArmed = false
        placeholderUp = false
        updateHeld = false
    }

    companion object {
        /**
         * Android's deadline for `startForeground` after `startForegroundService` is 10 s up to API 33 and longer from 34
         * (30 s seen on the API 35 emulator); keep a margin.
         */
        fun timeoutFor(sdk: Int): Long = if (sdk >= 34) 20_000L else 7_000L

        /**
         * Android 15+ refuses audio focus to an app targeting it unless it holds the foreground audio capability, and a
         * service only gets that from the actual `startForeground` (AOSP `OomAdjuster`; `startForegroundService` alone
         * gives the process state, not the capability). Media3 goes foreground only after `play()`, which asks for focus
         * at once, so a refused request would leave the title paused (`playWhenReady=false reason=2`). A key through the
         * platform session, or a Bluetooth controller connecting to a new session, happens to lift this for some seconds
         * (temporary while-in-use exemption); the first key after a reboot gets neither.
         */
        fun foregroundFirstFor(sdk: Int): Boolean = sdk >= 35

        /**
         * How long a placeholder keeps waiting for a slow resumption after the deadline. From API 35 it is up from the key,
         * so at most [timeoutFor] + this.
         */
        const val CAP_MS = 60_000L

        /** How long Media3 gets to finish a notification update before a detached placeholder is cancelled. */
        const val UPDATE_SETTLE_MS = 1_000L

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
