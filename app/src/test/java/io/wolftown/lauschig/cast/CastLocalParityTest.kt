package io.wolftown.lauschig.cast

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The review's probe, kept: a real ExoPlayer through Media3's real `CastPlayer` and [CastOutputPlayer], against a listener directly on it. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastLocalParityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After fun tearDown() = scope.cancel()

    private fun settle(times: Int) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        Thread.sleep(15)
    }

    private fun listener(into: ArrayList<String>) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { into += "error:${error.errorCodeName}" }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { into += "pwr:$playWhenReady:$reason" }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { into += "disc:$reason" }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { into += "transition:$reason" }
        override fun onIsPlayingChanged(isPlaying: Boolean) { into += "playing:$isPlaying" }
        override fun onPlaybackStateChanged(playbackState: Int) { into += "state:$playbackState" }
    }

    @Test fun eventsErrorsAndReasons_areTheExoPlayersOwn() {
        val exo = ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()
        val output = CastOutputPlayer(exo, FakeLink(), FakeSessions(), scope, {}, { false })
        val direct = ArrayList<String>()
        val through = ArrayList<String>()
        exo.addListener(listener(direct))
        output.addListener(listener(through))
        Cast.getSingletonInstance(context).initialize(CastParams.DEFAULT)
        output.attach(CastPlayer.Builder(context).setLocalPlayer(exo).setTransferCallback(output).build())
        settle(10)
        assertEquals("the attach on an empty player says nothing", emptyList<String>(), through)

        output.setMediaItem(MediaItem.fromUri("file:///nonexistent/a.mp3"), 7_000)
        output.prepare()
        output.play()
        settle(200)
        assertEquals(direct, through)
        assertEquals(exo.playerError?.errorCode, output.playerError?.errorCode)
        assertEquals(exo.currentPosition, output.currentPosition)

        direct.clear()
        through.clear()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        settle(10)
        assertEquals(direct.filter { it.startsWith("pwr") }, through.filter { it.startsWith("pwr") })
        output.release()
    }
}
