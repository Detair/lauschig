package io.wolftown.lauschig.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.wolftown.lauschig.auto.CarNode
import io.wolftown.lauschig.auto.MediaIds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Play keys that reach the service through `startForegroundService` (MediaButtonReceiver, the widget) but play
 * nothing must never let Android's deadline for `startForeground` pass: the app would be killed with
 * ForegroundServiceDidNotStartInTimeException (and this test process with it). Emulator-5554 with the test server.
 */
@RunWith(AndroidJUnit4::class)
class PlayKeyGuardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var browser: MediaBrowser

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> eventually(timeoutMs: Long = 20_000, check: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            main(check)?.let { return it }
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met within $timeoutMs ms")
            Thread.sleep(250)
        }
    }

    @Before fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        browser = main { MediaBrowser.Builder(context, token).buildAsync() }.get(20, TimeUnit.SECONDS)
    }

    @After fun release() {
        main { browser.pause(); browser.release() }
    }

    private fun headsetKey() = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackService::class.java)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))

    /** A held key: Android repeats the DOWN event; Media3 does not act on repeats, so nothing plays. */
    private fun heldPlayKey() = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackService::class.java)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 1))

    @Test fun headsetDoubleTap_onAPausedTitle_survivesTheDeadline_andKeepsTheNotification() =
        pausedTitle_survives { listOf(headsetKey(), headsetKey()) }

    @Test fun heldPlayKey_onAPausedTitle_survivesTheDeadline_andKeepsTheNotification() =
        pausedTitle_survives { listOf(heldPlayKey()) }

    private fun pausedTitle_survives(keys: () -> List<Intent>) {
        // A downloaded volume, loaded and paused (Media3 shows its notification).
        val end = System.currentTimeMillis() + 20_000
        var volume: androidx.media3.common.MediaItem? = null
        while (volume == null) {
            volume = main { browser.getChildren(MediaIds.of(CarNode.Series("Testreihe")), 0, 50, null) }.get(20, TimeUnit.SECONDS).value?.firstOrNull()
            if (volume == null && System.currentTimeMillis() > end) throw AssertionError("no Testreihe volume")
            if (volume == null) Thread.sleep(500)
        }
        main { browser.setMediaItem(volume); browser.prepare(); browser.play() }
        eventually { browser.isPlaying.takeIf { it } }
        main { browser.pause() }
        eventually { (!browser.isPlaying).takeIf { it } }

        // Two quick headset presses through the service, as MediaButtonReceiver delivers them: a double tap skips,
        // nothing plays, and Media3 never calls startForeground for that start.
        keys().forEach { context.startForegroundService(it) }
        // Past Android's deadline (10 s up to API 33, 30 s on the API 35 emulator).
        Thread.sleep(if (Build.VERSION.SDK_INT >= 34) 35_000 else 15_000)

        // Still alive (else this process would be gone), the title still loaded, Media3's notification still there.
        assertEquals(1, main { browser.mediaItemCount })
        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(nm.activeNotifications.any { it.id == ForegroundStartGuard.MEDIA_NOTIFICATION_ID })
    }
}
