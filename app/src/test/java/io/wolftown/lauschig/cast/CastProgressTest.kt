package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlaybackProgressBridge
import io.wolftown.lauschig.playback.ProgressTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * What a cast leaves behind, through the real bridge and tracker on the real database: the app's existing path with the
 * position from the player. A listen that moves to a receiver and back is one local session (which the sync sends), and the
 * server's session of the cast stays empty: so it counts once.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastProgressTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val device = FakeDevice()
    private val receiver = FakeReceiver()
    private val sessions = FakeSessions()
    private val link = FakeLink()
    private var now = 1_000_000L
    private lateinit var output: CastOutputPlayer
    private lateinit var cast: FakeCastPlayer
    private lateinit var bridge: PlaybackProgressBridge
    private val s = "srv"
    private val book = listOf(localItem(s, "li", 1), localItem(s, "li", 2))
    private val details = ItemWithDetails(
        ItemEntity(s, "li", "lib", "Titel", author = "Autorin", durationSec = 200.0), emptyList(),
        listOf(AudioFileEntity(s, "li", 1, "a", 100.0, 1, "mp3", null), AudioFileEntity(s, "li", 2, "b", 100.0, 1, "mp3", null)),
        null, null,
    )

    @Before fun setUp() {
        output = CastOutputPlayer(device, link, sessions, scope, {}, { false })
        cast = FakeCastPlayer(device, receiver, output)
        output.attach(cast)
        link.onEnd = {
            cast.sessionAvailable(false)
            output.onCastSessionEnded()
        }
        val tracker = ProgressTracker(db.progressDao(), db.sessionDao(), clock = { now }, scope = scope)
        bridge = PlaybackProgressBridge(output, tracker, { _, _ -> details }, scope)
        output.addListener(bridge)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Room answers on its own threads. */
    private fun settle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun playingHere(positionMs: Long = 5_000) {
        output.setMediaItems(book, 0, positionMs)
        output.prepare()
        output.play()
        settle()
    }

    private fun onReceiver() {
        cast.sessionAvailable(true)
        settle()
        receiver.loaded()
        settle()
    }

    /** A status with the position, and the player's second-by-second look at it. */
    private fun reported(positionMs: Long) {
        receiver.status(position = positionMs)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        settle()
    }

    private fun progress() = runBlocking { db.progressDao().get(s, "li") }
    private fun rows() = runBlocking { db.sessionDao().unsynced(s) }

    @Test fun aListenThatMovesThereAndBack_isOneSession_withItsWholeTime() {
        playingHere()
        now += 10_000
        onReceiver()
        now += 20_000
        reported(25_000)
        cast.sessionAvailable(false)
        settle()
        assertEquals(1, rows().size)
        val row = rows().single()
        assertEquals(5.0, row.startTimeSec, 0.01)
        assertEquals(25.0, row.currentTimeSec, 0.01)
        // 10 s here and 20 s there; the moment between the two counts for neither.
        assertEquals(30.0, row.timeListeningSec, 0.01)
    }

    @Test fun theProgress_comesFromThePlayer_mappedOverTheFiles() {
        playingHere()
        onReceiver()
        now += 5_000
        // Across the boundary: first the new file's start, then it plays on.
        receiver.status(Player.STATE_READY, index = 1, position = 500)
        settle()
        now += 30_000
        reported(30_000)
        output.pause()
        settle()
        val saved = progress()!!
        assertEquals(130.0, saved.currentTimeSec, 0.01)
        assertTrue(saved.dirty)
    }

    @Test fun whileTheReceiverLoads_nothingIsCounted_andThePositionStays() {
        playingHere()
        now += 10_000
        cast.sessionAvailable(true)
        settle()
        now += 60_000
        receiver.loaded()
        settle()
        cast.sessionAvailable(false)
        settle()
        assertEquals(10.0, rows().single().timeListeningSec, 0.01)
        assertEquals(5.0, rows().single().currentTimeSec, 0.01)
    }

    @Test fun theReceiverFinishing_marksTheTitleFinished() {
        output.setMediaItems(book, 1, 90_000)
        output.prepare()
        output.play()
        settle()
        onReceiver()
        now += 5_000
        reported(99_000)
        link.now = ReceiverActivity.FINISHED
        output.onReceiverStatus()
        settle()
        val saved = progress()!!
        assertTrue(saved.isFinished)
        assertEquals(200.0, saved.currentTimeSec, 0.01)
    }

    /** The data risks of the device test, through the real tracker: an early status with the wrong file or position moves nothing. */
    @Test fun aFirstStatusAtIndexZeroOrPositionZero_doesNotReachTheProgress() {
        output.setMediaItems(book, 1, 60_000)
        output.prepare()
        output.play()
        settle()
        now += 5_000
        cast.sessionAvailable(true)
        settle()
        receiver.status(Player.STATE_READY, index = 0, position = 0, paused = false)
        settle()
        receiver.status(Player.STATE_READY, index = 1, position = 0, paused = false)
        settle()
        output.pause()
        settle()
        assertEquals(160.0, progress()!!.currentTimeSec, 0.01)
        assertTrue(!progress()!!.isFinished)
    }

    @Test fun aFinishedStatusWhenTheIndexReachesTheLastFile_doesNotFinishTheTitle() {
        output.setMediaItems(book, 0, 90_000)
        output.prepare()
        output.play()
        settle()
        onReceiver()
        reported(99_000)
        link.now = ReceiverActivity.FINISHED
        output.onReceiverStatus()
        settle()
        receiver.status(Player.STATE_READY, index = 1, position = 0)
        settle()
        output.onReceiverStatus()
        settle()
        assertTrue(!progress()!!.isFinished)
    }

    @Test fun aNewTitleOnTheReceiver_savesTheOldOneWhereItWas() {
        playingHere()
        onReceiver()
        now += 5_000
        reported(40_000)
        output.setMediaItems(listOf(localItem(s, "other", 1), localItem(s, "other", 2)), 0, 0)
        settle()
        assertEquals(40.0, progress()!!.currentTimeSec, 0.01)
    }
}
