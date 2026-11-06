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

    /** The review's longer probe: seeks before and after the prepare, a second error, a stop and an emptied queue. */
    @Test fun seeksASecondErrorAStopAndAnEmptyQueue_areTheExoPlayersOwn() {
        val exo = ExoPlayer.Builder(context).build()
        val output = CastOutputPlayer(exo, FakeLink(), FakeSessions(), scope, {}, { false })
        val direct = ArrayList<String>()
        val through = ArrayList<String>()
        exo.addListener(listener(direct))
        output.addListener(listener(through))
        Cast.getSingletonInstance(context).initialize(CastParams.DEFAULT)
        output.attach(CastPlayer.Builder(context).setLocalPlayer(exo).setTransferCallback(output).build())
        settle(5)
        output.setMediaItems(listOf(MediaItem.fromUri("file:///nonexistent/a.mp3"), MediaItem.fromUri("file:///nonexistent/b.mp3")), 0, 3_000)
        output.seekTo(1, 2_000)
        output.prepare()
        output.seekTo(0, 500)
        settle(100)
        assertEquals(exo.playerError?.errorCode, output.playerError?.errorCode)
        output.seekTo(1, 700)
        output.prepare()
        settle(60)
        assertEquals(exo.playerError?.errorCode, output.playerError?.errorCode)
        assertEquals(exo.currentPosition, output.currentPosition)
        output.stop()
        settle(5)
        output.clearMediaItems()
        settle(5)
        assertEquals("error", 2, through.count { it.startsWith("error") })
        assertEquals(direct, through)
        assertEquals(0, output.mediaItemCount)
        output.release()
    }

    /** Minor 1 on a real ExoPlayer: while suspended no stream is loaded, so no error waits behind the mask for when the cast has ended. */
    @Test fun whileSuspended_aRealPlayerLoadsNothing_andFailsOnlyWhenTheTitleIsBack() {
        val exo = ExoPlayer.Builder(context).build()
        val receiver = FakeReceiver()
        val link = FakeLink()
        val output = CastOutputPlayer(exo, link, FakeSessions(), scope, {}, { false })
        val cast = FakeCastPlayer(exo, receiver, output)
        output.attach(cast)
        val events = ArrayList<String>()
        output.addListener(listener(events))
        output.setMediaItems(listOf(localItem("srv", "li", 1, "file:///nonexistent/1.mp3"), localItem("srv", "li", 2, "file:///nonexistent/2.mp3")), 0, 0)
        cast.sessionAvailable(true)
        settle(2)
        receiver.loaded()
        settle(2)
        events.clear()
        link.suspended = true
        output.onCastSuspended()
        cast.sessionAvailable(false)
        settle(100)
        assertEquals(Player.STATE_IDLE, exo.playbackState)
        assertEquals(null, exo.playerError)
        assertEquals(events.toString(), 0, events.count { it.startsWith("error") })
        output.onCastSessionEnded()
        settle(100)
        assertEquals(2, exo.mediaItemCount)
        assertEquals(events.toString(), 1, events.count { it.startsWith("error") })
        output.release()
    }
}
