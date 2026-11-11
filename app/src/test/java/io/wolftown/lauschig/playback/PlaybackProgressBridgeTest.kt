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
import io.wolftown.lauschig.db.entities.ProgressEntity
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

    /** Every `seekTo(index, positionMs)` the bridge asked for. */
    val seeks = mutableListOf<Pair<Int, Long>>()

    val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
        when (m.name) {
            "seekTo" -> {
                if (args?.size == 2 && args[0] is Int) { index = args[0] as Int; positionMs = args[1] as Long; seeks += index to positionMs }
                null
            }
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

    /** A is loaded and paused at 800 s, saved there. */
    private suspend fun TestScope.pausedA(): Pair<FakePlayerState, PlaybackProgressBridge> {
        val (p, br) = playingA()
        p.playing = false
        br.onIsPlayingChanged(false); runCurrent()
        assertEquals(800.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
        return p to br
    }

    /**
     * The title waits paused in the player while sync merges what another device heard. A play key that reaches the player
     * without passing the app (headset, car, widget, notification) continues there, and the stale position is never saved.
     */
    @Test fun playKey_afterSyncMovedThePausedTitle_continuesAtTheSyncedPosition() = runTest {
        val (p, br) = pausedA()
        db.progressDao().mergeFromServer(listOf(ProgressEntity(s, "A", 100.0, 900.0, false, currentTime + 1)))

        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()

        assertEquals(listOf(0 to 100_000L), p.seeks)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(100.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
    }

    /** Less than the jump threshold apart (the server rounds, a rewind after the pause): the player's own position stays. */
    @Test fun playKey_withTheSavedPositionCloseBy_doesNotSeek() = runTest {
        val (p, br) = pausedA()
        db.progressDao().mergeFromServer(listOf(ProgressEntity(s, "A", 790.0, 900.0, false, currentTime + 1)))
        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()
        assertEquals(emptyList<Pair<Int, Long>>(), p.seeks)
    }

    /** What the user did to the paused title is not "moved by someone else": a seek while paused stays where it went. */
    @Test fun playKey_afterASeekWhilePaused_staysWhereTheUserSeeked() = runTest {
        val (p, br) = pausedA()
        val old = p.positionInfo()
        p.index = 0; p.positionMs = 300_000
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_SEEK); runCurrent()
        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()
        assertEquals(emptyList<Pair<Int, Long>>(), p.seeks)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(300.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
    }

    /** The paused title started again somewhere else on purpose (a chapter, a bookmark: a fresh queue) is not pulled back. */
    @Test fun startingThePausedTitleElsewhere_isNotPulledBackToTheSavedPosition() = runTest {
        val (p, br) = pausedA()
        val old = p.positionInfo()
        p.items = factory.build(a, null); p.index = 0; p.positionMs = 50_000
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        br.onMediaItemTransition(p.items[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED); runCurrent()
        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()
        assertEquals(emptyList<Pair<Int, Long>>(), p.seeks)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(50.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
    }

    /**
     * Sync moved the paused title AND the user then starts it somewhere else on purpose (a fresh queue at a chapter): the
     * place the user chose wins. Only a title that still sits where it was paused is taken to the moved position.
     */
    @Test fun startingThePausedTitleElsewhere_afterSyncMovedIt_staysWhereItWasStarted() = runTest {
        val (p, br) = pausedA()
        db.progressDao().mergeFromServer(listOf(ProgressEntity(s, "A", 100.0, 900.0, false, currentTime + 1)))
        val old = p.positionInfo()
        p.items = factory.build(a, null); p.index = 0; p.positionMs = 400_000
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        br.onMediaItemTransition(p.items[0], Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED); runCurrent()
        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()
        assertEquals(emptyList<Pair<Int, Long>>(), p.seeks)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(400.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
    }

    /** The rewind after a pause (at most 30 s) has already moved the player a little: still a plain resume. */
    @Test fun playKey_afterSyncMovedThePausedTitle_andTheRewindAfterThePause_continuesAtTheSyncedPosition() = runTest {
        val (p, br) = pausedA()
        db.progressDao().mergeFromServer(listOf(ProgressEntity(s, "A", 100.0, 900.0, false, currentTime + 1)))
        p.positionMs -= 30_000 // PlaybackComfort's rewind, done before the bridge hears of the start
        p.playing = true
        br.onIsPlayingChanged(true); runCurrent()
        assertEquals(listOf(0 to 100_000L), p.seeks)
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

    @Test fun seekWhileNewBookBuffering_doesNotResaveOldBook() = runTest {
        val (p, br) = playingA()
        p.playing = false
        br.onIsPlayingChanged(false); runCurrent()
        val before = db.progressDao().get(s, "A")!!
        advanceTimeBy(60_000)
        val old = p.switchToB()
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        runCurrent()
        // B still buffering (not playing); the user seeks.
        val beforeSeek = p.positionInfo()
        p.positionMs = 120_000
        br.onPositionDiscontinuity(beforeSeek, p.positionInfo(), Player.DISCONTINUITY_REASON_SEEK)
        runCurrent()
        assertEquals(before, db.progressDao().get(s, "A"))
    }

    @Test fun seekWhilePaused_sameBook_isPersisted() = runTest {
        val (p, br) = playingA()
        p.playing = false
        br.onIsPlayingChanged(false); runCurrent()
        val beforeSeek = p.positionInfo()
        p.positionMs = 50_000 // second file, 50 s → global 650 s
        br.onPositionDiscontinuity(beforeSeek, p.positionInfo(), Player.DISCONTINUITY_REASON_SEEK)
        runCurrent()
        assertEquals(650.0, db.progressDao().get(s, "A")!!.currentTimeSec, 0.0)
    }

    @Test fun clearedQueue_endsThePlayer_butNeverMarksTheTitleFinished() = runTest {
        // A controller (car, watch, Assistant) clears the queue: ExoPlayer goes to ENDED with an empty timeline.
        val (p, br) = playingA()
        val old = p.positionInfo()
        p.items = emptyList(); p.index = 0; p.positionMs = 0; p.playing = false; p.state = Player.STATE_ENDED
        br.onPositionDiscontinuity(old, p.positionInfo(), Player.DISCONTINUITY_REASON_REMOVE)
        br.onIsPlayingChanged(false)
        br.onPlaybackStateChanged(Player.STATE_ENDED)
        runCurrent()
        val saved = db.progressDao().get(s, "A")!!
        assertEquals(800.0, saved.currentTimeSec, 0.0)
        assertFalse(saved.isFinished)
    }

    @Test fun currentGlobalSec_isNull_whenQueueHoldsAnotherItem() = runTest {
        val (p, br) = playingA()
        assertEquals(800.0, br.currentGlobalSec()!!, 0.0)
        p.switchToB()
        assertNull(br.currentGlobalSec())
    }
}
