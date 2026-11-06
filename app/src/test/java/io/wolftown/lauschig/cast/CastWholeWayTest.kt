package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.MediaItemFactory
import io.wolftown.lauschig.playback.PlayerControllerImpl
import io.wolftown.lauschig.playback.PlayerFacade
import io.wolftown.lauschig.playback.PlayerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The app's real controller on the real output player: a whole cast, from the app's side. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastWholeWayTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val home = "http://192.168.1.10:13378/"
    private val public = "https://abs.example.org/"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val device = FakeDevice()
    private val receiver = FakeReceiver()
    private val link = FakeLink()
    private val output = CastOutputPlayer(device, link, FakeSessions(listOf(public.toHttpUrl())), scope, {}, { false })
    private val cast = FakeCastPlayer(device, receiver, output)
    private val online = MutableStateFlow<HttpUrl?>(home.toHttpUrl())
    private val book = ItemWithDetails(
        ItemEntity(s, "it", "lib", "Title", author = "Author", durationSec = 200.0), emptyList(),
        listOf(AudioFileEntity(s, "it", 0, "a", 100.0, 1, "mp3", null), AudioFileEntity(s, "it", 1, "b", 100.0, 1, "mp3", null)), null, null,
    )

    /** What `MediaControllerFacade` does, on the player itself. */
    private inner class Direct : PlayerFacade {
        private var change: () -> Unit = {}
        init {
            output.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = change()
            })
        }
        override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            output.setMediaItems(items, startIndex, startPositionMs)
            output.prepare()
        }
        override fun replaceMediaItem(index: Int, item: MediaItem) = output.replaceMediaItem(index, item)
        override fun play() = output.play()
        override fun pause() = output.pause()
        override fun seekTo(mediaIndex: Int, positionMs: Long) = output.seekTo(mediaIndex, positionMs)
        override fun setSpeed(speed: Float) = output.setPlaybackSpeed(speed)
        override fun snapshot() = PlayerSnapshot(
            output.isPlaying, output.currentMediaItemIndex, output.currentPosition.coerceAtLeast(0), output.playbackParameters.speed,
            isBuffering = output.playWhenReady && output.playbackState == Player.STATE_BUFFERING,
        )
        override fun currentKey() = output.currentMediaItem?.titleKey()
        override fun hasMedia() = output.mediaItemCount > 0
        override fun setOnError(onError: (Int) -> Unit) = Unit
        override fun streamBase(): String? = (0 until output.mediaItemCount).firstNotNullOfOrNull { output.getMediaItemAt(it).mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_STREAM_BASE) }
        override fun setOnDisconnected(onDisconnected: () -> Unit) = Unit
        override fun setOnChange(onChange: () -> Unit) { change = onChange }
    }

    private val controller by lazy {
        PlayerControllerImpl(
            scope = scope, facade = Direct(), factory = MediaItemFactory({ true }) { _, _ -> null }, loadItem = { _, _ -> book },
            onlineBaseUrl = { online.value }, coverFile = { _, _ -> null }, io = Dispatchers.Main.immediate, onlineBase = online,
        )
    }

    @After fun tearDown() {
        // The controller's poll would keep the looper busy for ever.
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

    private fun uris() = device.items.map { it.localConfiguration!!.uri.toString() }

    /** I2: an address that changes during a cast is not lost. Back on this device the title streams from the new one. */
    @Test fun anAddressChangeDuringACast_thenPlayingHere_usesTheNewAddress() {
        link.onEnd = {
            cast.sessionAvailable(false)
            output.onCastSessionEnded()
        }
        output.attach(cast)
        controller.play(s, "it", startSec = 105.0)
        idle()
        assertTrue(uris().all { it.startsWith(home) })
        assertEquals(1, device.index)
        assertEquals(5_000, device.position)

        cast.sessionAvailable(true)
        idle()
        receiver.loaded()
        idle()
        assertEquals("the receiver has it from the server's address", 1, receiver.sent.size)

        // The phone left home with the receiver still going.
        online.value = public.toHttpUrl()
        idle()
        assertEquals(1, receiver.sent.size)
        receiver.status(position = 40_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // The cast ends: the title is back, paused, where it was, with the new address.
        link.endSession(stopReceiver = true)
        idle()
        idle()
        assertFalse(device.pwr)
        assertEquals(1, device.index)
        assertEquals(40_000, device.position)
        assertTrue(uris().toString(), uris().all { it.startsWith(public) })
        assertEquals(public, device.items.first().mediaMetadata.extras!!.getString(MediaItemFactory.EXTRA_STREAM_BASE))

        controller.togglePlayPause()
        idle()
        assertTrue(device.pwr)
        assertTrue(uris().all { it.startsWith(public) })
        assertEquals(1, receiver.sent.size)
    }
}
