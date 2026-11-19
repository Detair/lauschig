package io.wolftown.lauschig.playback

import android.app.Application
import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The wiring of [ForegroundStartGuard] in the real service, with Media3's real notification code behind it. The service
 * is not created (no Hilt): only what these seams need.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackServiceStartGuardTest {
    private class NoHost : ForegroundStartGuard.Host {
        var updates = 0
        override fun engaged() = false
        override fun hasItems() = false
        override fun adoptMediaNotification() = false
        override fun showPlaceholder() {}
        override fun leavePlaceholder() {}
        override fun cancelPlaceholder() {}
        override fun updateNotification() { updates++ }
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val service = Robolectric.buildService(PlaybackService::class.java).get()
    private val exo = ExoPlayer.Builder(context).build()
    private val session = MediaSession.Builder(context, exo).setId("guard-${System.nanoTime()}").build()
    private val host = NoHost()
    private val guard = ForegroundStartGuard(scope, host, foregroundFirst = true).also { service.startGuard = it }

    @After fun tearDown() {
        scope.cancel()
        session.release()
        exo.release()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun inTheForeground() {
        service.startForeground(4711, Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_media_play).build())
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test fun whileArmed_anUpdateWithoutForeground_leavesTheForegroundAlone() {
        guard.arm()
        inTheForeground()
        service.onUpdateNotificationAsync(session, false)
        idle()
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test fun whileArmed_afterARealUpdateOfThisInstance_theForegroundIsStillLeftAlone() {
        // Media3 remembers for good that its default update ran once: an override of onUpdateNotification alone is ignored
        // from then on. That was the warm instance: a browser bound, one key failed, the next key lost its foreground.
        service.onUpdateNotificationAsync(session, false)
        idle()
        assertTrue(shadowOf(service).isForegroundStopped) // not armed: Media3 does as it likes
        guard.arm()
        inTheForeground()
        service.onUpdateNotificationAsync(session, false)
        idle()
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test fun aHeldUpdate_runsWhenTheArmingEndsWithoutPlaying() {
        guard.arm()
        service.onUpdateNotificationAsync(session, false)
        idle()
        assertEquals(0, host.updates)
        guard.resumption(ok = false)
        assertEquals(1, host.updates)
    }

    @Test fun whileArmed_anUpdateThatWantsTheForeground_isNotHeld() {
        guard.arm()
        service.onUpdateNotificationAsync(session, true)
        idle()
        guard.resumption(ok = false)
        assertEquals(0, host.updates) // nothing was held back
    }

    @Test fun leavingThePlaceholder_stopsOnlyTheLastStartItKnows() {
        // A key on its way has a newer start id: Android then refuses the stop instead of crashing on the key's open deadline.
        service.onStartCommand(null, 0, 7)
        service.StartHost(exo).leavePlaceholder()
        assertEquals(7, shadowOf(service).stopSelfId)
    }

    /** With a car, the sound stays on this device: a cast that is on ends. */
    @Test fun aCarConnecting_endsARunningCast_andHidesTheButton() {
        val ends = ArrayList<Boolean>()
        val state = io.wolftown.lauschig.cast.CastState()
        service.castState = state
        service.castRuntime = object : io.wolftown.lauschig.cast.CastRuntime {
            override val supported = true
            override val started = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun start() = Unit
            override fun setDiscovery(on: Boolean) = Unit
            override fun newCastPlayer(device: androidx.media3.common.Player, transfer: androidx.media3.cast.CastPlayer.TransferCallback) = device
            override fun activity() = io.wolftown.lauschig.cast.ReceiverActivity.BUSY
            override fun contentId(): String? = null
            override fun isSuspended() = false
            override fun requestStatus() = Unit
            override fun endSession(stopReceiver: Boolean) { ends += stopReceiver }
            override fun setReceiverListener(listener: io.wolftown.lauschig.cast.ReceiverListener?) = Unit
        }
        var local = false
        service.output = object : PlaybackOutput {
            override val player: androidx.media3.common.Player get() = exo
            override val device: ExoPlayer get() = exo
            override val isLocal: Boolean get() = local
            override fun release() = Unit
        }
        service.carConnectionChanged(1)
        assertEquals(listOf(true), ends)
        assertTrue(state.ui.value.carConnected)
        service.carConnectionChanged(0)
        assertEquals(listOf(true), ends)
        assertFalse(state.ui.value.carConnected)
        local = true
        service.carConnectionChanged(1)
        assertEquals("nothing to end on this device", listOf(true), ends)
    }
}
