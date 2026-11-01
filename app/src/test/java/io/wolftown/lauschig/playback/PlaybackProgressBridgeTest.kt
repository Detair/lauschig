package io.wolftown.lauschig.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Minimal [Player] state; only what [PlaybackProgressBridge] reads. Built as a dynamic proxy. */
private class FakePlayerState {
    var items: List<MediaItem> = emptyList()
    var index = 0
    var positionMs = 0L
    var playing = false
    var state = Player.STATE_READY

    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, _ ->
        when (m.name) {
            "getCurrentMediaItem" -> items.getOrNull(index)
            "getCurrentMediaItemIndex" -> index
            "getCurrentPosition" -> positionMs
            "isPlaying" -> playing
            "getPlaybackState" -> state
            "getMediaItemCount" -> items.size
            else -> when (m.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                else -> null
            }
        }
    } as Player

    fun positionInfo() = Player.PositionInfo(null, index, items.getOrNull(index), null, index, positionMs, positionMs, -1, -1)
}

@RunWith(RobolectricTestRunner::class)
class PlaybackProgressBridgeTest {
    private lateinit var db: LauschigDatabase
    private val s = "aaaaaaaaaaaaaaaa"
    private val factory = MediaItemFactory({ true }) { _, _ -> null }

    private fun book(id: String, vararg durations: Double) = ItemWithDetails(
        ItemEntity(s, id, "lib", "Title $id", author = "Author", durationSec = durations.sum()),
        emptyList(),
        durations.mapIndexed { i, d -> AudioFileEntity(s, id, i, "ino$i", d, 1, "mp3", "/d/$id/$i.mp3") },
        null,
        null,
    )

    // A: 600 s + 300 s (900 s). B: one 1000 s file.
    private val a = book("A", 600.0, 300.0)
    private val b = book("B", 1000.0)

    @Before fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
    }

    @After fun tearDown() = db.close()

    private fun TestScope.bridge(p: FakePlayerState): PlaybackProgressBridge {
        val tracker = ProgressTracker(db.progressDao(), db.sessionDao(), clock = { currentTime }, scope = backgroundScope)
        val books = mapOf("A" to a, "B" to b)
        return PlaybackProgressBridge(p.player, tracker, { _, id -> books[id] }, backgroundScope)
    }

    /** A is playing in its second file at 200 s (global 800 s). */
    private fun TestScope.playingA(): Pair<FakePlayerState, PlaybackProgressBridge> {
        val p = FakePlayerState().apply { items = factory.build(a, null); index = 1; positionMs = 200_000; playing = true }
        val br = bridge(p)
        br.onIsPlayingChanged(true); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        return p to br
    }

    /** ExoPlayer replaced the queue with B at 899 s; A's mapper would read that as 899/900 = finished. */
    private fun FakePlayerState.switchToB(): Player.PositionInfo {
        val old = positionInfo()
        items = factory.build(b, null); index = 0; positionMs = 899_000; playing = false; state = Player.STATE_BUFFERING
        return old
    }

    @Test fun switchingBook_whilePlaying_savesOldBookAtItsOwnPosition_notAtNewQueueOffset() = runTest {
        val (p, br) = playingA()
        val old = p.switchToB()
        // ExoPlayer order: discontinuity (REMOVE), media item transition, isPlaying false.
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        br.onMediaItemTransition(p.items[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        br.onIsPlayingChanged(false)
        runCurrent()
        p.playing = true; p.state = Player.STATE_READY
        br.onIsPlayingChanged(true); runCurrent()

        val saved = db.progressDao().get(s, "A")!!
        assertEquals(800.0, saved.currentTimeSec, 0.0)
        assertFalse(saved.isFinished)
    }

    @Test fun switchingBook_transitionBeforeDiscontinuity_oldBookNeverReadsNewQueue() = runTest {
        val (p, br) = playingA()
        val old = p.switchToB()
        p.playing = true; p.state = Player.STATE_READY // playback continues without a pause event
        br.onMediaItemTransition(p.items[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        runCurrent()

        val saved = db.progressDao().get(s, "A")!!
        assertEquals(800.0, saved.currentTimeSec, 0.0) // last known position of A, not B's offset
        assertFalse(saved.isFinished)
    }

    @Test fun pausedOldBook_switch_doesNotResaveIt() = runTest {
        val (p, br) = playingA()
        p.playing = false
        br.onIsPlayingChanged(false); runCurrent()
        val before = db.progressDao().get(s, "A")!!
        advanceTimeBy(60_000)
        val old = p.switchToB()
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        br.onIsPlayingChanged(false); runCurrent()
        assertEquals(before, db.progressDao().get(s, "A"))
    }

    @Test fun currentGlobalSec_isNull_whenQueueHoldsAnotherItem() = runTest {
        val (p, br) = playingA()
        assertEquals(800.0, br.currentGlobalSec()!!, 0.0)
        p.switchToB()
        assertNull(br.currentGlobalSec())
    }
}
