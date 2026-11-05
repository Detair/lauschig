package io.wolftown.lauschig.playback

import android.app.Application
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The seam for Google Cast (Phase 6, Task 12.2): the service plays through a [PlaybackOutput]. Today that is this device
 * ([DeviceOutput]); a Cast output puts a player on top that may play on a receiver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackOutputTest {
    private val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()

    @After fun tearDown() {
        if (!exo.isReleased) exo.release()
    }

    @Test fun theDeviceOutput_isTheExoPlayer_playingHere() {
        val out = DeviceOutput(exo)
        assertSame(exo, out.player)
        assertSame(exo, out.device)
        assertTrue(out.isLocal)
    }

    @Test fun release_releasesTheDevicePlayer() {
        DeviceOutput(exo).release()
        assertTrue(exo.isReleased)
    }

    /** What a Cast output looks like to the service: another player on top of the device's, playing on a receiver. */
    @Test fun anOutputWhosePlayerPlaysElsewhere_isNotLocal_andKeepsTheDevicePlayerForTheEffects() {
        val receiver = object : ForwardingPlayer(exo) {
            override fun getDeviceInfo(): DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()
        }
        val out = object : PlaybackOutput {
            override val player: Player = receiver
            override val device: ExoPlayer = exo
            override fun release() = exo.release()
        }
        assertFalse(out.isLocal)
        assertSame(exo, out.device)
    }
}
