package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.DeviceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exo = ExoPlayer.Builder(context).build()
    private val state = CastState()

    @After fun tearDown() {
        scope.cancel()
        if (!exo.isReleased) exo.release()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FakeRuntime(override val supported: Boolean, private val cast: (Player, CastPlayer.TransferCallback) -> Player = { d, _ -> d }) : CastRuntime {
        override val started = MutableStateFlow(false)
        var discovery: Boolean? = null
        var listener: ReceiverListener? = null
        val built = ArrayList<Player>()
        override fun start() { if (supported) started.value = true }
        override fun setDiscovery(on: Boolean) { discovery = on }
        override fun newCastPlayer(device: Player, transfer: CastPlayer.TransferCallback): Player = cast(device, transfer).also { built += it }
        override fun activity() = ReceiverActivity.BUSY
        override fun contentId(): String? = null
        override fun isSuspended() = false
        override fun requestStatus() = Unit
        override fun endSession(stopReceiver: Boolean) = Unit
        override fun setReceiverListener(listener: ReceiverListener?) { this.listener = listener }
    }

    // ---- Without Play Services the app works as before.

    @Test fun withoutPlayServices_theOutputIsTheExoPlayerItself() {
        val runtime = FakeRuntime(supported = false)
        val out = playbackOutput(exo, runtime, FakeSessions(), state, scope)
        assertTrue(out is DeviceOutput)
        assertSame(exo, out.player)
        assertTrue(out.isLocal)
        runtime.start()
        idle()
        assertTrue(runtime.built.isEmpty())
        assertNullListener(runtime)
    }

    private fun assertNullListener(runtime: FakeRuntime) = assertEquals(null, runtime.listener)

    /** Robolectric has no Google Play services, the real check says what a phone without them says. */
    @Test fun theRealCheck_saysNo_andStartingDoesNothing() {
        val runtime = GmsCastRuntime(context, state)
        assertFalse(runtime.supported)
        runtime.start()
        runtime.setDiscovery(true)
        runtime.setDiscovery(false)
        runtime.endSession(stopReceiver = true)
        idle()
        assertFalse(runtime.started.value)
        assertEquals(CastUi(), state.ui.value)
        assertEquals(false, state.ui.value.showButton)
        assertEquals(ReceiverActivity.BUSY, runtime.activity())
    }

    /** Minor 5: with no service running nothing publishes: the line says "connected", only the player can say that something plays. */
    @Test fun aDeviceThatConnects_readsConnected_untilThePlayerSaysMore() {
        assertEquals(CastActivity.CONNECTED, CastUi().activity)
        assertEquals(CastActivity.CONNECTED, io.wolftown.lauschig.playback.PlayerUiState().outputActivity)
        assertEquals(CastActivity.CONNECTED, io.wolftown.lauschig.ui.player.PlayerScreenState().castActivity)
        val runtime = GmsCastRuntime(context, state)
        state.update { it.copy(activity = CastActivity.NONE) }
        runtime.sessions.onSessionStarted(anyCastSession(), "session")
        assertEquals(CastActivity.CONNECTED, state.ui.value.activity)
        state.update { it.copy(activity = CastActivity.PLAYING) }
        runtime.sessions.onSessionEnded(anyCastSession(), 0)
        assertEquals(CastActivity.CONNECTED, state.ui.value.activity)
    }

    @Test fun noButton_withoutARoute_orWithACar() {
        assertFalse(CastUi().showButton)
        assertTrue(CastUi(routeAvailable = true).showButton)
        assertFalse(CastUi(routeAvailable = true, carConnected = true).showButton)
    }

    // ---- With them, nothing of Cast runs until a person opens the player.

    @Test fun withPlayServices_theCastPlayerComesOnlyOnceStarted() {
        val runtime = FakeRuntime(supported = true)
        val out = playbackOutput(exo, runtime, FakeSessions(), state, scope)
        assertTrue(out is CastOutput)
        assertNotSame(exo, out.player)
        assertSame(exo, out.device)
        assertTrue(out.isLocal)
        idle()
        assertTrue(runtime.built.isEmpty())
        runtime.start()
        idle()
        assertEquals(1, runtime.built.size)
        assertTrue(out.isLocal)
        assertSame(out.player, runtime.listener)
        out.release()
        assertEquals(null, runtime.listener)
        assertTrue(exo.isReleased)
    }

    @Test fun aCastPlayerThatCannotBeBuilt_changesNothing() {
        val runtime = FakeRuntime(supported = true) { _, _ -> throw IllegalStateException("no framework") }
        val out = playbackOutput(exo, runtime, FakeSessions(), state, scope)
        runtime.start()
        idle()
        assertTrue(out.isLocal)
        assertEquals(Player.STATE_IDLE, out.player.playbackState)
    }

    /**
     * Media3's real `CastPlayer` and `RemoteCastPlayer` on a phone without Play Services, as far as the artifact allows without a
     * receiver: no session ever comes, the failed load of the framework stays inside, and the device plays through it.
     */
    @Test fun theRealCastPlayer_withoutAFramework_justForwardsToTheDevice() {
        val device = FakeDevice()
        Cast.getSingletonInstance(context).initialize(CastParams.DEFAULT)
        val output = CastOutputPlayer(device, FakeLink(), FakeSessions(), scope, {}, { false })
        output.attach(CastPlayer.Builder(context).setLocalPlayer(device).setTransferCallback(output).build())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        val book = listOf(localItem("srv", "li", 1), localItem("srv", "li", 2))
        output.setMediaItems(book, 1, 4_000)
        output.prepare()
        output.play()
        idle()
        assertEquals(book, device.items)
        assertTrue(device.pwr)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(4_000, output.currentPosition)
        assertTrue(output.isPlaying)
        output.pause()
        output.setPlaybackSpeed(1.5f)
        output.seekTo(0, 500)
        idle()
        assertFalse(device.pwr)
        assertEquals(1.5f, device.speed)
        assertEquals(500, device.position)
        output.stop()
        assertEquals(1, device.stopped)
        output.clearMediaItems()
        assertTrue(device.items.isEmpty())
        output.release()
    }

    // ---- Where the volume goes.

    /** The sleep fade goes where the sound is; on a receiver the device's own volume stays as it was. */
    @Test fun theVolume_goesWhereTheSoundIs() {
        val receiver = FakeReceiver()
        var cast: FakeCastPlayer? = null
        val runtime = FakeRuntime(supported = true) { d, t -> FakeCastPlayer(d, receiver, t).also { cast = it } }
        val out = playbackOutput(exo, runtime, FakeSessions(), state, scope)
        runtime.start()
        idle()
        out.setVolume(0.5f)
        assertEquals(0.5f, exo.volume, 0.001f)
        assertTrue(receiver.volumes.isEmpty())
        cast!!.sessionAvailable(true)
        idle()
        assertFalse(out.isLocal)
        out.setVolume(0.25f)
        assertEquals(listOf(0.25f), receiver.volumes)
        assertEquals(0.5f, exo.volume, 0.001f)
    }

    @Test fun withoutPlayServices_theVolumeIsTheExoPlayers() {
        val out = playbackOutput(exo, FakeRuntime(supported = false), FakeSessions(), state, scope)
        out.setVolume(0.3f)
        assertEquals(0.3f, exo.volume, 0.001f)
    }

    // ---- What the framework's callbacks do, through the real listener.

    private fun anyCastSession(): CastSession {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(CastSession::class.java) as CastSession
    }

    private class Heard : ReceiverListener {
        var ended = 0
        var suspended = 0
        var taken = 0
        override fun onReceiverStatus() = Unit
        override fun onReceiverError(detailedCode: Int?) = Unit
        override fun onCastSessionEnded() { ended++ }
        override fun onCastSuspended() { suspended++ }
        override fun onCastTakenBack() { taken++ }
    }

    private fun runtimeWith(heard: Heard) = GmsCastRuntime(context, state).also { it.setReceiverListener(heard) }

    private fun said(block: () -> Unit): List<Int> {
        val all = ArrayList<Int>()
        val job = scope.launch(Dispatchers.Unconfined) { state.messages.collect { all += it } }
        block()
        idle()
        job.cancel()
        return all
    }

    @Test fun aSessionsEnd_reachesThePlayer_andAPersonsOwnStopSaysNothing() {
        val heard = Heard()
        val runtime = runtimeWith(heard)
        state.update { it.copy(deviceName = "TV") }
        val messages = said { runtime.sessions.onSessionEnded(anyCastSession(), 0) }
        assertEquals(1, heard.ended)
        assertEquals(null, state.ui.value.deviceName)
        assertTrue(messages.isEmpty())
    }

    /** I4: the TV switched off, the network gone. */
    @Test fun aCastThatDrops_saysWhy() {
        val heard = Heard()
        val runtime = runtimeWith(heard)
        val messages = said { runtime.sessions.onSessionEnded(anyCastSession(), CastStatusCodes.NETWORK_ERROR) }
        assertEquals(1, heard.ended)
        assertEquals(listOf(R.string.cast_dropped), messages)
    }

    @Test fun aFailedResume_saysWhy_andIsAnEnd() {
        val heard = Heard()
        val runtime = runtimeWith(heard)
        val messages = said { runtime.sessions.onSessionResumeFailed(anyCastSession(), CastStatusCodes.TIMEOUT) }
        assertEquals(1, heard.ended)
        assertEquals(listOf(R.string.cast_dropped), messages)
    }

    @Test fun aCancelledStart_saysNothing_aFailedOneDoes() {
        val runtime = runtimeWith(Heard())
        assertTrue(said { runtime.sessions.onSessionStartFailed(anyCastSession(), CastStatusCodes.CANCELED) }.isEmpty())
        assertEquals(listOf(R.string.cast_connect_failed), said { runtime.sessions.onSessionStartFailed(anyCastSession(), CastStatusCodes.TIMEOUT) })
    }

    /** I1: a suspend keeps the device's name for the line, is no end, and the runtime says so. */
    @Test fun aSuspend_isNoEnd() {
        val heard = Heard()
        val runtime = runtimeWith(heard)
        state.update { it.copy(deviceName = "TV") }
        assertFalse(runtime.isSuspended())
        val messages = said { runtime.sessions.onSessionSuspended(anyCastSession(), 2) }
        assertEquals(1, heard.suspended)
        assertEquals(0, heard.ended)
        assertEquals("TV", state.ui.value.deviceName)
        assertTrue(runtime.isSuspended())
        assertTrue(messages.isEmpty())
        runtime.sessions.onSessionEnded(anyCastSession(), 0)
        assertFalse(runtime.isSuspended())
    }

    // ---- The receiver's status, as a table.

    @Test fun theStatusMapping() {
        val idle = MediaStatus.PLAYER_STATE_IDLE
        assertEquals(ReceiverActivity.FINISHED, receiverActivity(idle, MediaStatus.IDLE_REASON_FINISHED))
        assertEquals(ReceiverActivity.FAILED, receiverActivity(idle, MediaStatus.IDLE_REASON_ERROR))
        assertEquals(ReceiverActivity.STOPPED, receiverActivity(idle, MediaStatus.IDLE_REASON_CANCELED))
        assertEquals(ReceiverActivity.INTERRUPTED, receiverActivity(idle, MediaStatus.IDLE_REASON_INTERRUPTED))
        assertEquals(ReceiverActivity.BUSY, receiverActivity(idle, MediaStatus.IDLE_REASON_NONE))
        listOf(MediaStatus.PLAYER_STATE_PLAYING, MediaStatus.PLAYER_STATE_PAUSED, MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING, MediaStatus.PLAYER_STATE_UNKNOWN).forEach {
            assertEquals(ReceiverActivity.BUSY, receiverActivity(it, MediaStatus.IDLE_REASON_FINISHED))
        }
        assertEquals(ReceiverActivity.BUSY, receiverActivity(null, MediaStatus.IDLE_REASON_NONE))
    }

    // ---- The fade on a receiver.

    private fun receiverVolume(player: Player, clockMs: () -> Long) = ReceiverVolume({ player }, scope, minGapMs = 500, clock = clockMs)

    @Test fun theFade_usesTheStreamsVolume_andTheEndAlwaysGoesOut() {
        val receiver = FakeReceiver()
        var now = 1_000L
        val v = receiverVolume(receiver) { now }
        receiver.changed()
        v.set(0.8f)
        assertEquals(listOf(0.8f), receiver.volumes)
        now += 100
        v.set(0.7f)
        now += 100
        v.set(0.6f)
        // Not a message per tick: they wait for the gap, and only the newest goes.
        assertEquals(listOf(0.8f), receiver.volumes)
        now += 300
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(listOf(0.8f, 0.6f), receiver.volumes)
        now += 1
        // Restored at once, whatever waits.
        v.set(1f)
        assertEquals(listOf(0.8f, 0.6f, 1f), receiver.volumes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(0.8f, 0.6f, 1f), receiver.volumes)
    }

    @Test fun aReceiverWithoutStreamVolume_fadesTheDevicesVolume_andPutsItBack() {
        val receiver = FakeReceiver().apply { volumeCommand = false }
        var now = 1_000L
        val v = receiverVolume(receiver) { now }
        receiver.changed()
        v.set(0.5f)
        assertEquals(listOf(0), receiver.deviceVolumes.map { it - it })
        val base = receiver.deviceVolume
        assertEquals(listOf(Math.round(base * 0.5f)), receiver.deviceVolumes)
        now += 1000
        v.set(1f)
        assertEquals(base, receiver.deviceVolumes.last())
        assertTrue(receiver.volumes.isEmpty())
    }
}
