package io.wolftown.lauschig.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class CarSafePlayerTest {
    /** ExoPlayer as the session sees it: without a next item it does not offer "next" at all. */
    private class Fake {
        var durations = listOf(600_000L)
        var index = 0
        var position = 0L
        var commands: Player.Commands = Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .build()
        val seeks = mutableListOf<Pair<Int, Long>>()
        val directVolumes = mutableListOf<Float>()
        val listeners = mutableListOf<Player.Listener>()

        fun item(i: Int) = MediaItem.Builder().setMediaId("b#$i").setMediaMetadata(
            MediaMetadata.Builder().setExtras(Bundle().apply { putLong(MediaItemFactory.EXTRA_FILE_DURATION_MS, durations[i]) }).build(),
        ).build()

        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getMediaItemCount" -> durations.size
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> position
                "getDuration" -> durations.getOrElse(index) { 0L }
                "getMediaItemAt" -> item(args!![0] as Int)
                "getAvailableCommands" -> commands
                "isCommandAvailable" -> commands.contains(args!![0] as Int)
                "seekTo" -> { if (args?.size == 2) seeks += (args[0] as Int) to (args[1] as Long); null }
                "setVolume" -> { directVolumes += args!![0] as Float; null }
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as Player
    }

    private val nextCommands = listOf(
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    @Test fun oneFileBook_offersNext_andNextMovesThirtySeconds() {
        val f = Fake().apply { position = 100_000 }
        val p = CarSafePlayer(f.player, 30_000, 30_000)
        nextCommands.forEach { assertTrue("command $it", p.isCommandAvailable(it)) }
        nextCommands.forEach { assertTrue("command $it", p.availableCommands.contains(it)) }
        p.seekToNext()
        p.seekToNextMediaItem()
        p.seekToPrevious()
        assertEquals(listOf(0 to 130_000L, 0 to 130_000L, 0 to 70_000L), f.seeks)
    }

    @Test fun lastFileOfABook_nextStopsAtTheEnd() {
        val f = Fake().apply { durations = listOf(600_000L, 300_000L); index = 1; position = 290_000 }
        CarSafePlayer(f.player, 30_000, 30_000).seekToNext()
        assertEquals(listOf(1 to 300_000L), f.seeks)
    }

    @Test fun nothingLoaded_offersNothingExtra() {
        val f = Fake().apply { durations = emptyList() }
        val p = CarSafePlayer(f.player, 30_000, 30_000)
        assertFalse(p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        p.seekToNext()
        assertTrue(f.seeks.isEmpty())
    }

    @Test fun listeners_hearTheWidenedCommands_andEverythingElse() {
        val f = Fake()
        val p = CarSafePlayer(f.player, 30_000, 30_000)
        var heard: Player.Commands? = null
        var playing: Boolean? = null
        var eventsFrom: Player? = null
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { heard = availableCommands }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onEvents(player: Player, events: Player.Events) { eventsFrom = player }
        }
        p.addListener(listener)
        f.listeners.single().onAvailableCommandsChanged(f.commands)
        f.listeners.single().onIsPlayingChanged(true)
        f.listeners.single().onEvents(f.player, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
        assertTrue(heard!!.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertEquals(true, playing)
        assertTrue("events report the session's player", eventsFrom === p)
        p.removeListener(listener)
        assertTrue(f.listeners.isEmpty())
    }

    @Test fun asymmetricSteps_backAndForwardMoveByTheirOwnSeconds() {
        val f = Fake().apply { position = 100_000 }
        val p = CarSafePlayer(f.player, backMs = 15_000, forwardMs = 30_000)
        p.seekToPrevious()
        p.seekBack()
        p.seekToNext()
        p.seekForward()
        assertEquals(listOf(0 to 85_000L, 0 to 85_000L, 0 to 130_000L, 0 to 130_000L), f.seeks)
        assertEquals(15_000L, p.seekBackIncrement)
        assertEquals(30_000L, p.seekForwardIncrement)
    }

    @Test fun newSteps_applyAtOnce_andListenersHearTheIncrements() {
        val f = Fake().apply { position = 100_000 }
        val p = CarSafePlayer(f.player, 15_000, 30_000)
        val heard = mutableListOf<String>()
        p.addListener(object : Player.Listener {
            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) { heard += "back $seekBackIncrementMs" }
            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) { heard += "forward $seekForwardIncrementMs" }
        })
        p.setSteps(backMs = 10_000, forwardMs = 45_000)
        p.step(forward = false)
        p.step(forward = true)
        assertEquals(listOf(0 to 90_000L, 0 to 145_000L), f.seeks)
        assertEquals(10_000L, p.seekBackIncrement)
        assertEquals(45_000L, p.seekForwardIncrement)
        assertEquals(listOf("back 10000", "forward 45000"), heard)
        // The same steps again change nothing and tell no one.
        p.setSteps(10_000, 45_000)
        assertEquals(2, heard.size)
    }

    @Test fun aControllersVolume_goesThroughThePlayerVolume() {
        val f = Fake()
        val applied = mutableListOf<Float>()
        val volume = PlayerVolume { applied += it }
        val p = CarSafePlayer(f.player, 15_000, 30_000, volume)
        volume.setSwitchGain(0.5f)
        p.volume = 0.6f // the sleep timer's fade, via the session
        assertEquals(0.6f, volume.requested, 0f)
        assertEquals(0.3f, applied.last(), 0.0001f)
        assertTrue("written around the owner: ${f.directVolumes}", f.directVolumes.isEmpty())
    }

    @Test fun theWrapperForwardsEveryListenerCallback() {
        // Guards against Media3 adding a callback the wrapper would swallow.
        val wrapper = Class.forName("io.wolftown.lauschig.playback.WidenedListener")
        val missing = Player.Listener::class.java.declaredMethods.filter { m ->
            runCatching { wrapper.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
        }.map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
