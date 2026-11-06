package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaError
import io.wolftown.lauschig.R
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The player on a receiver, between the app and `RemoteCastPlayer`. [FakeCastPlayer] does what Media3's `CastPlayer` does at a
 * change of output, and [FakeReceiver] what `RemoteCastPlayer` reports, in the orders the review named.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastOutputPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val device = FakeDevice()
    private val receiver = FakeReceiver { now }
    private val sessions = FakeSessions()
    private val said = ArrayList<Int>()
    private val activities = ArrayList<CastActivity>()
    private var car = false
    private var now = 1_000_000L
    private val link = FakeLink()
    private lateinit var output: CastOutputPlayer
    private lateinit var cast: FakeCastPlayer
    private val states = ArrayList<Int>()
    private val book = listOf(localItem("srv", "li", 1), localItem("srv", "li", 2))

    @Before fun setUp() {
        output = CastOutputPlayer(device, link, sessions, scope, { said += it }, { car }, clock = { now }, publish = { activities += it })
        cast = FakeCastPlayer(device, receiver, output)
        output.attach(cast)
        link.onEnd = {
            cast.sessionAvailable(false)
            output.onCastSessionEnded()
        }
        output.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { states += playbackState }
        })
    }

    @After fun tearDown() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    /** Time passes: for the player's timers and its clock. */
    private fun pass(seconds: Long) = repeat(seconds.toInt()) {
        now += 1000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }
    private fun connect() { cast.sessionAvailable(true); idle() }
    /** The framework ends the Cast session: the transfer, then the end. */
    private fun ended() {
        cast.sessionAvailable(false)
        output.onCastSessionEnded()
        idle()
    }
    private fun suspendSession() {
        link.suspended = true
        output.onCastSuspended()
        cast.sessionAvailable(false)
        idle()
    }
    private fun resumeSession() {
        link.suspended = false
        cast.sessionAvailable(true)
        idle()
    }
    private fun status(activity: ReceiverActivity) { link.now = activity; output.onReceiverStatus(); idle() }

    private fun playingHere(items: List<MediaItem> = book, index: Int = 1, positionMs: Long = 5_000) {
        output.setMediaItems(items, index, positionMs)
        output.prepare()
        output.play()
        idle()
    }

    private fun onReceiverNow() {
        playingHere()
        connect()
        receiver.loaded()
        idle()
    }

    /** The receiver plays on, and it is at [positionMs] two seconds after a status (the player looks once a second). */
    private fun playedTo(positionMs: Long) {
        val moves = receiver.pwr && receiver.playback == Player.STATE_READY
        receiver.status(position = if (moves) positionMs - 2_000 else positionMs)
        pass(2)
    }

    /** A new status: the Cast player has it (its index, its queue), and the runtime's callback tells the player. */
    private fun told(state: Int = Player.STATE_READY, index: Int? = null, position: Long? = null, paused: Boolean? = null) {
        receiver.status(state, index, position, paused)
        idle()
        output.onReceiverStatus()
        idle()
    }

    private fun uris(sent: FakeReceiver.Sent) = sent.items.map { it.localConfiguration!!.uri.toString() }

    // ---- On the device nothing changes.

    @Test fun onTheDevice_everythingGoesThrough_beforeAndAfterTheCastPlayer() {
        val other = FakeDevice()
        val direct = CastOutputPlayer(other, link, sessions, scope, { said += it }, { car })
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, direct.deviceInfo.playbackType)
        direct.setMediaItems(book, 1, 7_000)
        direct.prepare()
        direct.play()
        idle()
        assertEquals(book, other.items)
        assertTrue(direct.isPlaying)
        assertEquals(7_000, direct.currentPosition)
        assertEquals(1, direct.currentMediaItemIndex)
        direct.seekTo(0, 1_000)
        direct.setPlaybackSpeed(1.5f)
        assertEquals(1_000, other.position)
        assertEquals(1.5f, other.speed)
        direct.pause()
        assertFalse(other.pwr)
        assertEquals(0, sessions.opens.size)

        playingHere()
        assertEquals(book, device.items)
        assertTrue(output.isPlaying)
        assertEquals(0, sessions.opens.size)
    }

    // ---- Local to cast.

    @Test fun aSessionAddressGoesToTheReceiver_atThePositionItWasAt() {
        playingHere()
        connect()
        assertEquals(listOf("srv" to "li"), sessions.opens)
        val sent = receiver.sent.single()
        assertEquals(listOf("https://abs.example/public/session/sess-1/track/1", "https://abs.example/public/session/sess-1/track/2"), uris(sent))
        assertEquals(1, sent.index)
        assertEquals(5_000, sent.position)
        assertTrue(sent.playWhenReady)
    }

    @Test fun aDownloadedTitle_isCastFromTheServerToo() {
        val files = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "file:///data/x/2.mp3"))
        playingHere(files)
        connect()
        assertTrue(uris(receiver.sent.single()).all { it.startsWith("https://abs.example/public/session/") })
    }

    /** The hard rule again, at the end of the whole path: every field of every item that reached the receiver. */
    @Test fun nothingBuiltForAReceiverHoldsAToken_orADeviceAddress() {
        val files = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "https://abs.example/api/items/li/file/2/download?token=secret#srv"))
        playingHere(files)
        connect()
        receiver.sent.single().items.forEach { item ->
            val json = ReceiverConverter().toMediaQueueItem(item).toJson().toString()
            listOf("token", "secret", "Bearer", "file:", "content:", "#srv", "download", "streamBase").forEach { assertFalse("$it in $json", json.contains(it)) }
            assertNull(item.localConfiguration!!.uri.fragment)
            assertNull(item.localConfiguration!!.uri.query)
        }
    }

    @Test fun whileTheReceiverLoads_theTitleStaysInThePlayer_atItsPosition_asBuffering() {
        playingHere()
        connect()
        assertEquals(2, output.mediaItemCount)
        assertEquals("srv" to "li", output.currentMediaItem!!.titleKey())
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(5_000, output.currentPosition)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        assertTrue(output.playWhenReady)
        assertFalse(output.isPlaying)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
    }

    @Test fun thereIsNoLocalFocusWhileCasting() {
        playingHere()
        connect()
        assertFalse(device.pwr)
        assertEquals(1, device.stopped)
        assertEquals(Player.STATE_IDLE, device.playbackState)
    }

    @Test fun onceTheReceiverHasIt_itsOwnStateIsThePlayers_andTheSpeedGoesAfter() {
        playingHere()
        output.setPlaybackSpeed(1.5f)
        connect()
        assertEquals(1.5f, output.playbackParameters.speed)
        receiver.loaded()
        idle()
        receiver.status(position = 6_000)
        idle()
        assertEquals(Player.STATE_READY, output.playbackState)
        assertTrue(output.isPlaying)
        assertEquals(6_000, output.currentPosition)
        assertEquals(1.5f, receiver.speed)
    }

    @Test fun nothingLoadedWhenTheCastStarts_nothingIsSent_untilATitleStarts() {
        connect()
        assertEquals(0, sessions.opens.size)
        assertEquals(0, output.mediaItemCount)
        assertEquals(CastActivity.CONNECTED, activities.last())
        output.setMediaItems(book, 0, 2_000)
        output.prepare()
        output.play()
        idle()
        assertEquals(listOf("srv" to "li"), sessions.opens)
        assertEquals(2_000, receiver.sent.single().position)
        assertTrue(output.playWhenReady)
        assertEquals(CastActivity.PLAYING, activities.last())
    }

    // ---- Guards: no position from the receiver reaches the tracker until the load is proven.

    @Test fun theStartFileAtIndexZeroOfN_isNoProof_thePositionStands() {
        playingHere()
        connect()
        // The status came before the queue's ids: READY, at index 0 of N, a position in the wrong file.
        receiver.status(Player.STATE_READY, index = 0, position = 5_000, paused = false)
        idle()
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(5_000, output.currentPosition)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        assertFalse(output.isPlaying)
        assertEquals("asked for a fresh status", 1, link.statusRequests)
        // The fresh status names the file.
        receiver.status(Player.STATE_READY, index = 1, position = 5_500)
        idle()
        assertEquals(Player.STATE_READY, output.playbackState)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(5_500, output.currentPosition)
        assertTrue(output.isPlaying)
    }

    @Test fun positionZeroInTheFirstStatus_isNoProof_thePositionStands() {
        playingHere(index = 1, positionMs = 60_000)
        connect()
        receiver.status(Player.STATE_READY, index = 1, position = 0, paused = false)
        idle()
        assertEquals(60_000, output.currentPosition)
        assertFalse(output.isPlaying)
        assertEquals("asked for a fresh status", 1, link.statusRequests)
        // Only a new status can correct it.
        told(position = 61_000)
        assertEquals(61_000, output.currentPosition)
        assertTrue(output.isPlaying)
    }

    @Test fun thePositionOfThePreviousCast_isNoProof() {
        playingHere(index = 1, positionMs = 5_000)
        connect()
        // `lastReportedPositionMs` survives the session: the first status of a new cast may still say the old one.
        receiver.status(Player.STATE_READY, index = 1, position = 90_000, paused = false)
        idle()
        assertEquals(5_000, output.currentPosition)
        assertFalse(output.isPlaying)
        ended()
        assertEquals(5_000, device.position)
    }

    @Test fun queueIdsThatComeLate_keepTheTitleInThePlayer() {
        playingHere()
        connect()
        // PLAYING before the queue's ids: nothing shown yet, and no way for the player to say so.
        receiver.queueGone()
        idle()
        assertEquals(2, output.mediaItemCount)
        assertEquals(5_000, output.currentPosition)
        receiver.loaded()
        idle()
        assertTrue(output.isPlaying)
    }

    // ---- R3: a receiver that plays the title is never told off for a status that cannot say where.

    /** READY is a reason to ask, whether the item is known or not, and again every few seconds. */
    @Test fun readyAtAnUnknownItem_isAReasonToAsk_everyFewSeconds() {
        playingHere()
        connect()
        val sent = receiver.sent.last().items
        receiver.items = listOf(MediaItem.EMPTY) + sent.drop(1)
        receiver.status(Player.STATE_READY, index = 0, position = 5_000, paused = false)
        idle()
        pass(1)
        assertEquals("at once", 1, link.statusRequests)
        pass(5)
        assertEquals(2, link.statusRequests)
        pass(50)
        assertTrue(link.statusRequests.toString(), link.statusRequests >= 10)
    }

    /** The queue's item 0 is unknown and the index falls back to it, but the status's content id names the start file: that is the proof. */
    @Test fun theContentId_namesTheStartFile_atAPlausiblePosition_isProof_alsoAtAStaleIndex() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere()
        connect()
        val sent = receiver.sent.last().items
        receiver.items = listOf(MediaItem.EMPTY) + sent.drop(1)
        link.content = sent[1].mediaId
        told(Player.STATE_READY, index = 0, position = 5_000, paused = false)
        assertEquals("a proven cast", CastActivity.PLAYING, activities.last())
        // Media3 has not placed it: the player stays at the title's own file, and nothing wrong reaches the progress.
        assertEquals(1, output.currentMediaItemIndex)
        assertFalse(output.isPlaying)
        pass(70)
        assertEquals("never another address", 1, receiver.sent.size)
        assertTrue("never a word about reaching it", said.isEmpty())
        assertTrue(link.ends.isEmpty())
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals("it follows the receiver meanwhile", 75_000, output.currentPosition)
        // The queue is there.
        receiver.items = sent
        told(Player.STATE_READY, index = 1, position = 76_000)
        assertTrue(output.isPlaying)
        assertEquals(76_000, output.currentPosition)
    }

    /** It plays the title, and through the whole limit nothing says a plausible place. The TV stays on, nothing else moves, and it is said once. */
    @Test fun aProofThatNeverComes_leavesTheTvPlaying_withNoOtherAddress_andSaysSoOnce() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere(index = 1, positionMs = 60_000)
        connect()
        val sent = receiver.sent.last().items
        link.content = sent[0].mediaId
        told(Player.STATE_READY, index = 0, position = 0, paused = false)
        pass(65)
        assertEquals(listOf(R.string.cast_position_unknown), said)
        assertTrue("the TV is not stopped", link.ends.isEmpty())
        assertEquals("no other address", 1, receiver.sent.size)
        assertTrue(receiver.pwr)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
        assertEquals("nothing reaches the progress", 60_000, output.currentPosition)
        assertEquals(1, output.currentMediaItemIndex)
        assertFalse(output.isPlaying)
        assertEquals(CastActivity.PLAYING, activities.last())
        pass(200)
        assertEquals("once", listOf(R.string.cast_position_unknown), said)
        assertTrue(link.ends.isEmpty())
        // A plausible status after all: an ordinary cast.
        link.content = sent[1].mediaId
        told(Player.STATE_READY, index = 1, position = 300_000)
        assertTrue(output.isPlaying)
        assertEquals(300_000, output.currentPosition)
    }

    /** The way out for a person: a seek reaches the TV, and what it says next is the proof. */
    @Test fun aSeekWhileThatLasts_reachesTheTv_andIsTheProof() {
        playingHere(index = 1, positionMs = 60_000)
        connect()
        val sent = receiver.sent.last().items
        link.content = sent[0].mediaId
        told(Player.STATE_READY, index = 0, position = 0, paused = false)
        pass(65)
        output.seekTo(1, 20_000)
        idle()
        assertEquals(1 to 20_000L, receiver.seeks.last())
        link.content = sent[1].mediaId
        told(Player.STATE_READY, index = 1, position = 20_500)
        assertTrue(output.isPlaying)
        assertEquals(20_500, output.currentPosition)
        assertEquals(1, receiver.sent.size)
    }

    @Test fun aStopWhileThatLasts_leavesTheTitleHere_atTheLastProvenPlace() {
        playingHere(index = 1, positionMs = 60_000)
        connect()
        link.content = receiver.sent.last().items[0].mediaId
        receiver.status(Player.STATE_READY, index = 0, position = 0, paused = false)
        idle()
        pass(65)
        output.stop()
        idle()
        assertEquals(listOf(true), link.ends)
        assertEquals(1, device.index)
        assertEquals(60_000, device.position)
        assertFalse(device.pwr)
    }

    /** The TV's own stop while that lasts: the title waits paused here, as after any stop from elsewhere. */
    @Test fun aReceiverThatStopsWhileThatLasts_isStopped() {
        playingHere(index = 1, positionMs = 60_000)
        connect()
        link.content = receiver.sent.last().items[0].mediaId
        receiver.status(Player.STATE_READY, index = 0, position = 0, paused = false)
        idle()
        pass(65)
        status(ReceiverActivity.STOPPED)
        assertEquals(CastActivity.CONNECTED, activities.last())
        assertFalse(output.playWhenReady)
        assertEquals(1, receiver.sent.size)
    }

    /** The review's other case: one ask, and the one answer says 0 too. Asked again, a later answer is the proof. */
    @Test fun positionZero_andTheOneAnswerToo_isAskedAgain() {
        playingHere(index = 1, positionMs = 60_000)
        connect()
        receiver.status(Player.STATE_READY, index = 1, position = 0, paused = true)
        idle()
        pass(2)
        receiver.status(Player.STATE_READY, index = 1, position = 500, paused = true)
        pass(10)
        assertTrue(link.statusRequests >= 3)
        assertEquals(60_000, output.currentPosition)
        told(Player.STATE_READY, index = 1, position = 60_000, paused = false)
        assertTrue(output.isPlaying)
    }

    /** Minor 6: a later failure names the address that really was tried. */
    @Test fun aFailureAfterALongWait_namesTheAddressItUsed() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere(index = 1, positionMs = 60_000)
        connect()
        link.content = receiver.sent.last().items[0].mediaId
        receiver.status(Player.STATE_READY, index = 0, position = 0, paused = false)
        idle()
        pass(65)
        link.content = null
        status(ReceiverActivity.FAILED)
        assertEquals("the next address, really sent", 2, receiver.sent.size)
        assertTrue(uris(receiver.sent.last()).first().startsWith("http://192.168.1.2"))
        status(ReceiverActivity.BUSY)
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_position_unknown, R.string.cast_unreachable_http), said)
    }

    // ---- Minor 3: a title cast again that is finished.

    /** Here it waits at its end, as ExoPlayer does at the end of its queue. A receiver that finishes it at once is that, not a network problem. */
    @Test fun castingATitleAtItsEnd_thatTheReceiverFinishesAtOnce_isTheEnd() {
        playingHere(index = 1, positionMs = 99_000)
        connect()
        receiver.status(Player.STATE_IDLE, index = 1, position = 99_000)
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        assertFalse(output.playWhenReady)
        assertTrue("no message: nothing went wrong", said.isEmpty())
        assertEquals(CastActivity.CONNECTED, activities.last())
        pass(65)
        assertTrue(said.isEmpty())
        assertEquals(Player.STATE_ENDED, output.playbackState)
        // The press the session turns a play at the end into: from the start of the file, as here.
        output.seekToDefaultPosition()
        idle()
        assertEquals(2, receiver.sent.size)
        assertEquals(0, receiver.sent.last().position)
    }

    /** Anywhere else it is a prompt, plain failure: no address is at fault and nothing waits for the limit. */
    @Test fun aReceiverThatFinishesATitleAtOnce_elsewhere_saysSoPlainly_withoutTheLimit() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere(index = 0, positionMs = 30_000)
        connect()
        receiver.status(Player.STATE_IDLE, index = 0, position = 0)
        status(ReceiverActivity.FINISHED)
        pass(5)
        assertEquals(listOf(R.string.cast_finished_at_once), said)
        assertEquals(1, receiver.sent.size)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(0, device.index)
        assertEquals(30_000, device.position)
        assertFalse(device.pwr)
    }

    /** A moment of it between two files, for a load right at a file's end, ends nothing. */
    @Test fun aMomentOfFinishedInALoad_endsNothing() {
        playingHere(index = 0, positionMs = 99_000)
        connect()
        receiver.status(Player.STATE_IDLE, index = 0, position = 100_000)
        status(ReceiverActivity.FINISHED)
        pass(1)
        link.now = ReceiverActivity.BUSY
        told(Player.STATE_READY, index = 1, position = 1_000, paused = false)
        assertTrue(said.isEmpty())
        assertTrue(output.isPlaying)
        assertEquals(1, output.currentMediaItemIndex)
    }

    /** The status still says the title's end from the last time: only what comes after the load counts. */
    @Test fun aFinishedStatusLeftFromBefore_isNotTheNewLoads() {
        onReceiverNow()
        playedTo(99_000)
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        output.seekTo(0, 2_000)
        idle()
        assertEquals(2, receiver.sent.size)
        output.onReceiverStatus()
        pass(5)
        assertTrue(said.isEmpty())
        assertEquals(CastActivity.PLAYING, activities.last())
        assertEquals(2_000, output.currentPosition)
    }

    // ---- Minor 2: only the next file is a crossing.

    /** The index falls back to 0 for a status whose item the player cannot place: nothing moved, and a cast ending right then leaves it where it was. */
    @Test fun aFallbackToIndexZero_isNoCrossing_andThePositionStands() {
        onReceiverNow()
        playedTo(60_000)
        told(Player.STATE_READY, index = 0, position = 61_000)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(60_000, output.currentPosition)
        assertFalse(output.isPlaying)
        ended()
        assertEquals(1, device.index)
        assertEquals(60_000, device.position)
    }

    @Test fun aFallbackToIndexZero_thatPasses_changesNothing() {
        onReceiverNow()
        playedTo(60_000)
        receiver.status(Player.STATE_READY, index = 0, position = 61_000)
        pass(1)
        told(Player.STATE_READY, index = 1, position = 62_000)
        assertTrue(output.isPlaying)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(62_000, output.currentPosition)
    }

    /** Back across a file: the status that overtook the seek still has the old file, one further on than the target, which is no crossing. */
    @Test fun aSeekBackAcrossFiles_withALateStatus_isNoCrossing() {
        onReceiverNow()
        playedTo(60_000)
        output.seekTo(0, 40_000)
        idle()
        told(Player.STATE_READY, index = 1, position = 61_000)
        pass(1)
        assertEquals(0, output.currentMediaItemIndex)
        assertEquals(40_000, output.currentPosition)
        told(Player.STATE_READY, index = 0, position = 40_500)
        assertTrue(output.isPlaying)
        assertEquals(0, output.currentMediaItemIndex)
        assertEquals(40_500, output.currentPosition)
    }

    /** Forward across a file: the same. */
    @Test fun aSeekForwardAcrossFiles_withALateStatus_isNoCrossing() {
        playingHere(index = 0, positionMs = 5_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(60_000)
        output.seekTo(1, 40_000)
        idle()
        told(Player.STATE_READY, index = 0, position = 61_000)
        pass(1)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(40_000, output.currentPosition)
    }

    /** A jump that lasts is what the receiver does (a TV's remote). */
    @Test fun anotherFileForLonger_isWhatTheReceiverPlays() {
        val three = listOf(localItem("srv", "li", 1), localItem("srv", "li", 2), localItem("srv", "li", 3))
        sessions.tracksFor = { 3 }
        playingHere(three, 2, 5_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(30_000)
        told(Player.STATE_READY, index = 0, position = 10_000)
        pass(5)
        assertEquals(2, output.currentMediaItemIndex)
        assertEquals(30_000, output.currentPosition)
        pass(7)
        assertEquals(0, output.currentMediaItemIndex)
        assertEquals(22_000, output.currentPosition)
        assertTrue(output.isPlaying)
    }

    @Test fun aFileBoundaryOnTheReceiver_neverPairsTheNewFileWithTheOldPosition() {
        playingHere(index = 0, positionMs = 90_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(99_500)
        // The status moved the index; `lastReportedPositionMs` still says the last file's.
        receiver.status(Player.STATE_READY, index = 1)
        idle()
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(0, output.currentPosition)
        assertFalse(output.isPlaying)
        // The next status: the new file really.
        told(position = 800)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(800, output.currentPosition)
        assertTrue(output.isPlaying)
    }

    // ---- Guards: a title is finished only at the true end of its last file.

    @Test fun theReceiverFinishing_atTheTrueEnd_isTheEndOfTheTitle_forTheProgressAndTheQueue() {
        playingHere(index = 1, positionMs = 90_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(99_000)
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        assertEquals(Player.STATE_ENDED, states.last())
        assertEquals(2, output.mediaItemCount)
        assertEquals("srv" to "li", output.currentMediaItem!!.titleKey())
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(100_000, output.currentPosition)
        assertEquals(CastActivity.CONNECTED, activities.last())

        // The queue's next title goes where the sound is.
        val next = listOf(localItem("srv", "next", 1), localItem("srv", "next", 2))
        output.setMediaItems(next, 0, 0)
        output.prepare()
        output.play()
        idle()
        assertEquals("srv" to "next", sessions.opens.last())
        assertEquals("https://abs.example/public/session/sess-2/track/1", uris(receiver.sent.last()).first())
    }

    @Test fun aFinishedFileInTheMiddle_isNoEnd() {
        playingHere(index = 0, positionMs = 90_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(99_000)
        status(ReceiverActivity.FINISHED)
        assertFalse(states.contains(Player.STATE_ENDED))
    }

    /** The review's probe: the status still says finished when the index reaches the last file. */
    @Test fun finishedStill_whenTheIndexReachesTheLastFile_isNoEnd() {
        playingHere(index = 0, positionMs = 90_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(99_000)
        status(ReceiverActivity.FINISHED)
        receiver.status(Player.STATE_READY, index = 1, position = 0)
        idle()
        output.onReceiverStatus()
        idle()
        assertFalse(states.contains(Player.STATE_ENDED))
        assertEquals(1, output.currentMediaItemIndex)
    }

    @Test fun finishedAnywhereElseInTheLastFile_isNoEnd_andTheQueueDoesNotStart() {
        playingHere(index = 1, positionMs = 20_000)
        connect()
        receiver.loaded()
        idle()
        status(ReceiverActivity.FINISHED)
        assertFalse(states.contains(Player.STATE_ENDED))
        assertEquals(20_000, output.currentPosition)
        // If it stays that way the receiver holds nothing: the title waits paused, at that position, and the play button loads it.
        pass(12)
        assertFalse(states.contains(Player.STATE_ENDED))
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        assertEquals(20_000, output.currentPosition)
        assertEquals(CastActivity.CONNECTED, activities.last())
    }

    /** An idle status may report position 0: it does not reach the progress. */
    @Test fun anIdleStatusWithPositionZero_doesNotMoveThePosition() {
        onReceiverNow()
        playedTo(40_000)
        link.now = ReceiverActivity.FINISHED
        receiver.status(Player.STATE_IDLE, position = 0, paused = false)
        idle()
        assertEquals(40_000, output.currentPosition)
        assertEquals(1, output.currentMediaItemIndex)
    }

    @Test fun aSeekAfterTheEnd_loadsAgain_withANewSession() {
        playingHere(index = 1, positionMs = 90_000)
        connect()
        receiver.loaded()
        idle()
        playedTo(99_000)
        status(ReceiverActivity.FINISHED)
        output.seekTo(0, 1_000)
        idle()
        assertEquals(2, sessions.opens.size)
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(2, receiver.sent.size)
        assertEquals("https://abs.example/public/session/sess-2/track/1", uris(receiver.sent.last()).first())
        assertEquals(1_000, receiver.sent.last().position)
    }

    // ---- What cannot be cast.

    /** Only the system's output switcher gets this far. The session ends again, nothing reaches the receiver, the player says why. */
    @Test fun aDeviceTitle_isRefused_andWaitsWhereItWas() {
        val title = listOf(deviceItem())
        playingHere(title, 0, 3_000)
        connect()
        assertEquals(0, sessions.opens.size)
        assertEquals(listOf(true), link.ends)
        assertEquals(listOf(R.string.cast_refused_device_title), said)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(title, device.items)
        assertEquals(3_000, output.currentPosition)
        assertFalse(device.pwr)
        assertTrue(receiver.sent.isEmpty())
    }

    @Test fun aDeviceTitleStartedWhileCasting_waitsHere_paused_andTheCastEnds() {
        onReceiverNow()
        val title = listOf(deviceItem())
        output.setMediaItems(title, 0, 0)
        output.play()
        idle()
        assertEquals(listOf(R.string.cast_refused_device_title), said)
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(1, receiver.sent.size)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(title, device.items)
        assertFalse(device.pwr)
    }

    @Test fun withACarConnected_playbackStaysHere() {
        car = true
        playingHere()
        connect()
        assertEquals(0, sessions.opens.size)
        assertTrue(said.isEmpty())
        assertEquals(listOf(true), link.ends)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(5_000, output.currentPosition)
        assertTrue(device.pwr)
    }

    /** Minor 1: the old title may not go out once the cast is on its way out. */
    @Test fun aSessionThatOpensWhileTheCastIsEnding_isNotSent() {
        val slow = CompletableDeferred<Unit>()
        sessions.gate = slow
        link.onEnd = {} // the framework takes its time
        playingHere()
        connect()
        output.setMediaItems(listOf(deviceItem()), 0, 0)
        idle()
        slow.complete(Unit)
        idle()
        assertTrue(receiver.sent.isEmpty())
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    // ---- Back to the device.

    @Test fun takingItBack_keepsThePosition_withTheDevicesOwnItems_paused() {
        onReceiverNow()
        playedTo(42_000)
        ended()
        assertEquals(book, device.items)
        assertEquals(1, device.index)
        assertEquals(42_000, device.position)
        assertFalse(device.pwr)
        assertTrue(device.prepared >= 2)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(42_000, output.currentPosition)
        assertTrue(device.items.none(ReceiverItems::isReceiverItem))
        assertEquals(CastActivity.NONE, activities.last())
    }

    /** Minor 3: a session lost right after the start does not restore a position the previous cast left in the player. */
    @Test fun aSessionLostRightAfterTheStart_restoresWhereItStarted() {
        onReceiverNow()
        playedTo(90_000)
        ended()
        output.seekTo(0, 3_000)
        output.play()
        idle()
        connect()
        receiver.status(Player.STATE_READY, index = 0, paused = false) // `lastReportedPositionMs` still 90 s
        idle()
        ended()
        assertEquals(0, device.index)
        assertEquals(3_000, device.position)
    }

    @Test fun theSpeedAPersonChose_comesBackWithTheTitle_alsoAboveTheReceiversLimit() {
        val asked = ArrayList<Float>()
        output.onSpeedRequested = { asked += it }
        onReceiverNow()
        output.setPlaybackSpeed(2.5f)
        idle()
        assertEquals(listOf(2.5f), asked)
        assertEquals(2f, receiver.speed)
        ended()
        assertEquals(2.5f, device.speed)
    }

    @Test fun onTheDevice_aSpeedIsNoRequest() {
        val asked = ArrayList<Float>()
        output.onSpeedRequested = { asked += it }
        playingHere()
        output.setPlaybackSpeed(1.25f)
        assertTrue(asked.isEmpty())
        assertEquals(1.25f, device.speed)
    }

    @Test fun thePhonesOutputSwitcher_continuesAPlayingTitleHere_afterTheEnd() {
        onReceiverNow()
        ended()
        assertFalse(device.pwr)
        output.onCastTakenBack()
        assertTrue(device.pwr)
    }

    /** Minor 2: in the other order as well. */
    @Test fun thePhonesOutputSwitcher_continuesAPlayingTitleHere_beforeTheEnd() {
        onReceiverNow()
        output.onCastTakenBack()
        ended()
        assertTrue(device.pwr)
    }

    @Test fun thePhonesOutputSwitcher_doesNotStartAPausedTitle_orOneFromLongAgo() {
        onReceiverNow()
        output.pause()
        idle()
        output.onCastTakenBack()
        ended()
        assertFalse(device.pwr)
        output.onCastTakenBack()
        assertFalse(device.pwr)

        output.play()
        idle()
        connect()
        receiver.loaded()
        idle()
        ended()
        output.pause()
        now += 60_000
        output.onCastTakenBack()
        assertFalse(device.pwr)
    }

    @Test fun theVolumeFollowsTheTitle() {
        var changes = 0
        output.onOutputChanged = { changes++ }
        onReceiverNow()
        assertEquals(1, changes)
        ended()
        assertEquals(2, changes)
    }

    // ---- The session.

    @Test fun anotherTitle_opensItsOwnSession_andTheOldOneCloses() {
        onReceiverNow()
        val other = listOf(localItem("srv", "other", 1), localItem("srv", "other", 2))
        output.setMediaItems(other, 0, 1_000)
        idle()
        assertEquals(listOf("srv" to "li", "srv" to "other"), sessions.opens)
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals("srv" to "other", output.currentMediaItem!!.titleKey())
        assertEquals("https://abs.example/public/session/sess-2/track/1", uris(receiver.sent.last()).first())
        assertEquals(1_000, receiver.sent.last().position)
    }

    @Test fun aSessionThatOpensTooLate_isClosedAgain() {
        val slow = CompletableDeferred<Unit>()
        sessions.gate = slow
        playingHere()
        connect()
        ended()
        slow.complete(Unit)
        idle()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertTrue(receiver.sent.isEmpty())
    }

    /** Minor 5: A, B, A with B still opening; the server closes A's session when B opens. */
    @Test fun aTitleAgain_getsANewSession_neverTheOneTheServerMayHaveClosed() {
        onReceiverNow()
        val slow = CompletableDeferred<Unit>()
        sessions.gate = slow
        output.setMediaItems(listOf(localItem("srv", "other", 1), localItem("srv", "other", 2)), 0, 0)
        idle()
        output.setMediaItems(book, 1, 5_000)
        idle()
        slow.complete(Unit)
        sessions.gate = null
        idle()
        assertEquals(1, receiver.sent.size - 1)
        assertEquals("sess-3", uris(receiver.sent.last()).first().substringAfter("session/").substringBefore('/'))
    }

    @Test fun theSameTitleWithNewSources_reachesOnlyTheWayBack() {
        onReceiverNow()
        val downloaded = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "file:///data/x/2.mp3"))
        output.setMediaItems(downloaded, receiver.index, receiver.position)
        idle()
        assertEquals(1, sessions.opens.size)
        assertEquals(1, receiver.sent.size)
        assertTrue(receiver.seeks.isEmpty())
        ended()
        assertEquals(downloaded, device.items)
    }

    @Test fun theSameTitleAtAnotherPosition_isASeek() {
        onReceiverNow()
        output.setMediaItems(book, 0, 70_000)
        idle()
        assertEquals(1, sessions.opens.size)
        assertEquals(listOf(0 to 70_000L), receiver.seeks)
    }

    @Test fun theChapterInTheNotification_neverTouchesTheReceiversQueue() {
        onReceiverNow()
        val current = output.currentMediaItem!!
        output.replaceMediaItem(output.currentMediaItemIndex, current)
        idle()
        assertEquals(0, receiver.replaced)
        assertEquals(1, receiver.sent.size)
        ended()
        assertTrue(device.items.none(ReceiverItems::isReceiverItem))
    }

    @Test fun queueEditsNeverReachTheReceiver() {
        onReceiverNow()
        output.addMediaItem(localItem("srv", "x", 1))
        output.moveMediaItem(0, 1)
        output.removeMediaItem(0)
        idle()
        assertEquals(1, receiver.sent.size)
        assertEquals(0, link.ends.size)
    }

    @Test fun anEndedSession_closesTheServerSession() {
        onReceiverNow()
        ended()
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    // ---- I1: a suspended Cast session (a network hiccup) leaves the running receiver alone.

    @Test fun aSuspendAndResume_doesNotTouchTheReceiver_andTakesItsPosition() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        assertTrue(sessions.closed.isEmpty())
        assertEquals(1, receiver.sent.size)
        resumeSession()
        // The receiver played on meanwhile, and says so with its first status.
        receiver.status(Player.STATE_READY, index = 1, position = 90_000, paused = false)
        idle()
        assertEquals("no second load", 1, receiver.sent.size)
        assertEquals(1, sessions.opens.size)
        assertTrue(sessions.closed.isEmpty())
        assertTrue("not paused", receiver.pwr)
        assertTrue(output.isPlaying)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(90_000, output.currentPosition)
        assertEquals(CastActivity.PLAYING, activities.last())
    }

    @Test fun aResumeAsksForAFreshStatus() {
        onReceiverNow()
        val before = link.statusRequests
        suspendSession()
        resumeSession()
        assertEquals(before + 1, link.statusRequests)
    }

    /** While suspended the phone cannot know: it shows neither playing nor paused, plays nothing and controls nothing. */
    @Test fun whileSuspended_thePhoneMakesNoSound_andPretendsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        assertEquals(CastActivity.SUSPENDED, activities.last())
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        assertFalse(output.isPlaying)
        assertEquals(30_000, output.currentPosition)
        assertFalse(device.pwr)
        output.play()
        output.seekTo(0, 1_000)
        output.setPlaybackSpeed(1.5f)
        idle()
        assertFalse("no sound here", device.pwr)
        assertEquals(30_000, output.currentPosition)
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(1f, output.playbackParameters.speed)
        output.pause()
        idle()
        assertTrue("no pretended pause", output.playWhenReady)
        resumeSession()
        receiver.status(Player.STATE_READY, index = 1, position = 40_000, paused = false)
        idle()
        assertTrue(receiver.seeks.isEmpty())
        assertTrue(receiver.pwr)
        assertTrue(output.isPlaying)
    }

    /** The framework's order is not documented: the transfer may come before the news of the suspend. */
    @Test fun theSuspendAfterTheTransfer_isASuspendToo() {
        onReceiverNow()
        playedTo(30_000)
        cast.sessionAvailable(false)
        idle()
        link.suspended = true
        output.onCastSuspended()
        idle()
        assertEquals(CastActivity.SUSPENDED, activities.last())
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        assertEquals("no stream on this device", Player.STATE_IDLE, device.playback)
        resumeSession()
        receiver.status(Player.STATE_READY, index = 1, position = 40_000, paused = false)
        idle()
        assertEquals(1, receiver.sent.size)
        assertTrue(output.isPlaying)
    }

    @Test fun aResume_whereTheReceiverLostTheTitle_loadsIt_withWhatItWasDoing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        status(ReceiverActivity.FAILED)
        assertEquals(2, receiver.sent.size)
        assertEquals(30_000, receiver.sent.last().position)
        assertEquals(1, receiver.sent.last().index)
        assertTrue("it was playing", receiver.sent.last().playWhenReady)
        // Its session is still good: the server knows nothing of the hiccup.
        assertEquals(1, sessions.opens.size)
    }

    @Test fun aResume_withoutAnyAnswer_loadsAfterTheLimit() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        pass(9)
        assertEquals(1, receiver.sent.size)
        pass(3)
        assertEquals(2, receiver.sent.size)
        assertEquals(30_000, receiver.sent.last().position)
    }

    // ---- R2: only a failure or silence sends again. A client may keep its old status through a suspend, or not: both are here.

    /** Stopped at the TV during the gap: a client without a status hears it first, and nothing restarts. */
    @Test fun aResume_whereItWasStoppedOnPurpose_startsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        status(ReceiverActivity.STOPPED)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        assertEquals(30_000, output.currentPosition)
        assertEquals(CastActivity.CONNECTED, activities.last())
        // A press loads, as after any stop.
        output.play()
        idle()
        assertEquals(2, receiver.sent.size)
        assertEquals(30_000, receiver.sent.last().position)
        assertTrue(receiver.sent.last().playWhenReady)
    }

    /** The same with a client that kept its old status: the title looks alive, until the fresh status says otherwise. */
    @Test fun aResume_withTheOldStatusKept_thenStopped_startsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        told(position = 30_000)
        assertEquals("the old status is enough to go on", 1, receiver.sent.size)
        status(ReceiverActivity.STOPPED)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertFalse(output.playWhenReady)
        assertEquals(CastActivity.CONNECTED, activities.last())
    }

    @Test fun aResume_whereItPlayedToTheEnd_isTheEnd_andStartsNothing() {
        onReceiverNow()
        playedTo(90_000)
        suspendSession()
        resumeSession()
        status(ReceiverActivity.FINISHED)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        assertFalse(output.playWhenReady)
    }

    @Test fun aResume_withFinishedElsewhere_isStopped_andStartsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        status(ReceiverActivity.FINISHED)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        assertEquals(30_000, output.currentPosition)
    }

    @Test fun aResume_withTheOldStatusKept_thenFinishedElsewhere_startsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        told(position = 30_000)
        status(ReceiverActivity.FINISHED)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
    }

    /** Another sender's title after the gap is no silence: it plays, and it stays. */
    @Test fun aResume_withAnotherSendersTitle_sendsNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        resumeSession()
        link.content = "someone-elses-title"
        told(position = 1_000)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertFalse(output.playWhenReady)
    }

    /** A title stopped before the suspend goes back to that, whichever status the client has: only a press loads. */
    @Test fun aTitleThatWasStoppedBeforeTheSuspend_goesBackToThat_withoutALoad() {
        onReceiverNow()
        status(ReceiverActivity.STOPPED)
        suspendSession()
        assertEquals(CastActivity.SUSPENDED, activities.last())
        // A client without its old status says nothing yet, and the silence is no reason here.
        link.now = ReceiverActivity.BUSY
        resumeSession()
        assertEquals(CastActivity.CONNECTED, activities.last())
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        output.play()
        idle()
        assertEquals(2, receiver.sent.size)
    }

    @Test fun aTitleThatHadEndedBeforeTheSuspend_goesBackToThat_withoutALoad() {
        onReceiverNow()
        playedTo(90_000)
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        suspendSession()
        link.now = ReceiverActivity.BUSY
        resumeSession()
        assertEquals(Player.STATE_ENDED, output.playbackState)
        pass(12)
        assertEquals("no load", 1, receiver.sent.size)
        assertEquals(Player.STATE_ENDED, output.playbackState)
    }

    // ---- M1: while suspended no stream is prepared here: it may all come back, and the phone may have no network.

    @Test fun whileSuspended_nothingIsPreparedHere_untilTheTitleReallyComesBack() {
        onReceiverNow()
        playedTo(30_000)
        val prepared = device.prepared
        suspendSession()
        pass(5)
        assertEquals("no stream on this device", prepared, device.prepared)
        assertEquals(Player.STATE_IDLE, device.playback)
        assertEquals(2, output.mediaItemCount)
        assertEquals(30_000, output.currentPosition)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        output.onCastSessionEnded()
        idle()
        assertEquals(prepared + 1, device.prepared)
        assertEquals(book, device.items)
        assertEquals(1, device.index)
        assertEquals(30_000, device.position)
        assertEquals(Player.STATE_READY, device.playback)
        assertFalse(device.pwr)
    }

    /** Back again: the receiver goes on, and nothing was ever prepared here. */
    @Test fun aSuspendThatComesBack_preparedNothingHere() {
        onReceiverNow()
        playedTo(30_000)
        val prepared = device.prepared
        suspendSession()
        resumeSession()
        told(position = 40_000)
        assertEquals(prepared, device.prepared)
        assertEquals(1, receiver.sent.size)
        assertTrue(output.isPlaying)
    }

    @Test fun whileSuspended_newSources_areForTheWayBack_andPrepareNothing() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        val downloaded = listOf(localItem("srv", "li", 1, "file:///d/1.mp3"), localItem("srv", "li", 2, "file:///d/2.mp3"))
        output.setMediaItems(downloaded, 1, 30_000)
        output.replaceMediaItem(0, localItem("srv", "li", 1, "file:///d/1b.mp3"))
        idle()
        assertEquals(Player.STATE_IDLE, device.playback)
        output.onCastSessionEnded()
        idle()
        assertEquals("file:///d/1b.mp3", device.items[0].localConfiguration!!.uri.toString())
        assertEquals("file:///d/2.mp3", device.items[1].localConfiguration!!.uri.toString())
    }

    @Test fun aStopWhileSuspended_leavesTheTitleHere_notPrepared() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        val prepared = device.prepared
        output.stop()
        idle()
        assertEquals(prepared, device.prepared)
        assertEquals(book, device.items)
        assertEquals(30_000, device.position)
        assertFalse(device.pwr)
    }

    // ---- Minor 4.

    /** "This phone" during a suspend: no sound that cannot be paused; the session's end brings it here, playing, as it was asked. */
    @Test fun thePhonesOutputSwitcher_whileSuspended_makesNoSound_untilTheSessionEnds() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        output.onCastTakenBack()
        idle()
        assertFalse(device.pwr)
        output.onCastSessionEnded()
        idle()
        assertTrue(device.pwr)
        assertEquals(30_000, device.position)
        output.pause()
        idle()
        assertFalse(device.pwr)
    }

    @Test fun thePhonesOutputSwitcher_whileSuspended_isForgottenIfTheSessionComesBack() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        output.onCastTakenBack()
        resumeSession()
        told(position = 40_000)
        ended()
        assertFalse(device.pwr)
    }

    @Test fun aPausedCast_staysPausedAcrossASuspend() {
        onReceiverNow()
        output.pause()
        idle()
        playedTo(30_000)
        suspendSession()
        assertFalse(output.playWhenReady)
        resumeSession()
        receiver.status(Player.STATE_READY, index = 1, position = 30_000, paused = true)
        idle()
        assertFalse(output.playWhenReady)
        assertEquals(1, receiver.sent.size)
    }

    @Test fun aSuspendThatEnds_leavesTheTitleHere_paused_atTheLastPosition() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        output.onCastSessionEnded()
        idle()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(30_000, output.currentPosition)
        assertFalse(device.pwr)
        assertEquals(CastActivity.NONE, activities.last())
    }

    /** The framework may never say: a phone that stays away gives up, and says so. */
    @Test fun aSuspendThatNeverEnds_isGivenUpAfterFiveMinutes() {
        onReceiverNow()
        playedTo(30_000)
        suspendSession()
        pass(4 * 60)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
        pass(61)
        assertEquals(listOf(R.string.cast_dropped), said)
        assertEquals(listOf(true), link.ends)
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(30_000, output.currentPosition)
        assertFalse(device.pwr)
    }

    @Test fun aSwitchWhileSuspended_endsItAll() {
        onReceiverNow()
        suspendSession()
        output.stop()
        output.clearMediaItems()
        idle()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(listOf(true), link.ends)
        assertEquals(0, output.mediaItemCount)
        assertTrue(device.items.isEmpty())
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
    }

    /** The app's path: the items, a prepare, and the play that belongs to them. The text says it waits here, so it does; a person's press afterwards plays. */
    @Test fun anotherTitleWhileSuspended_waitsHere_paused_andTheCastEnds() {
        onReceiverNow()
        suspendSession()
        val other = listOf(localItem("srv", "other", 1))
        output.setMediaItems(other, 0, 1_000)
        output.prepare()
        output.play()
        assertFalse("the play of the same request starts nothing", device.pwr)
        idle()
        assertEquals(listOf(R.string.cast_suspended_title_here), said)
        assertEquals(other, device.items)
        assertEquals(1_000, device.position)
        assertFalse(device.pwr)
        assertFalse(output.playWhenReady)
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(listOf(true), link.ends)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        // The framework has said the session is over.
        output.play()
        idle()
        assertTrue(device.pwr)
        assertEquals(1, receiver.sent.size)
    }

    /** The framework may never say: a short bound, and then a press plays. */
    @Test fun anotherTitleWhileSuspended_andTheFrameworkSaysNothing_aPressPlaysAfterTheBound() {
        link.onEnd = {}
        onReceiverNow()
        suspendSession()
        val other = listOf(localItem("srv", "other", 1))
        output.setMediaItems(other, 0, 1_000)
        output.prepare()
        output.play()
        idle()
        assertFalse(device.pwr)
        output.onCastTakenBack()
        idle()
        assertFalse("the window for taking the cast back was the old title's", device.pwr)
        pass(3)
        output.play()
        idle()
        assertFalse("still the hold", device.pwr)
        pass(3)
        output.play()
        idle()
        assertTrue(device.pwr)
        output.pause()
        idle()
        assertFalse(device.pwr)
    }

    /** A seek or a pause in the hold is the device's: only a play waits. */
    @Test fun anotherTitleWhileSuspended_aSeekInTheHold_isTheDevices() {
        link.onEnd = {}
        onReceiverNow()
        suspendSession()
        output.setMediaItems(listOf(localItem("srv", "other", 1), localItem("srv", "other", 2)), 0, 1_000)
        output.seekTo(1, 2_000)
        idle()
        assertEquals(1, device.index)
        assertEquals(2_000, device.position)
    }

    // ---- R4: a new Cast session on another day: what the old one left does not come back.

    private fun aNewCastAfterAnEnd_startsFromHere_inTheStateHere(end: () -> Unit) {
        onReceiverNow()
        playedTo(30_000)
        end()
        idle()
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(30_000, device.position)
        // An hour on this device: another file, another position, paused again.
        output.seekTo(0, 77_000)
        output.play()
        idle()
        output.pause()
        idle()
        connect()
        assertEquals("loaded at once, no resume that waits", 2, receiver.sent.size)
        assertEquals(0, receiver.sent.last().index)
        assertEquals(77_000, receiver.sent.last().position)
        assertFalse(receiver.sent.last().playWhenReady)
        assertEquals(0, output.currentMediaItemIndex)
        assertEquals(77_000, output.currentPosition)
        assertFalse(output.playWhenReady)
    }

    @Test fun aNewCastAfterAnEnd_theTransferFirst_startsFromHere() = aNewCastAfterAnEnd_startsFromHere_inTheStateHere { ended() }

    /** The TV switched off, or stopped there: no `onSessionEnding`, the runtime's listener hears the end before Media3's transfer. */
    @Test fun aNewCastAfterAnEnd_theEndFirst_startsFromHere() = aNewCastAfterAnEnd_startsFromHere_inTheStateHere {
        output.onCastSessionEnded()
        cast.sessionAvailable(false)
    }

    /** An end before the transfer still leaves the title here, and closes its session once. */
    @Test fun anEndBeforeTheTransfer_leavesTheTitleHere() {
        onReceiverNow()
        playedTo(30_000)
        output.onCastSessionEnded()
        cast.sessionAvailable(false)
        idle()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(book, device.items)
        assertEquals(1, device.index)
        assertEquals(30_000, device.position)
        assertFalse(device.pwr)
    }

    @Test fun aNewCastAfterARealEnd_startsFromTheDevice() {
        onReceiverNow()
        playedTo(30_000)
        ended()
        output.seekTo(0, 2_000)
        idle()
        connect()
        assertEquals(2, receiver.sent.size)
        assertEquals(0, receiver.sent.last().index)
        assertEquals(2_000, receiver.sent.last().position)
    }

    // ---- A switch or a sign-out.

    /** What `PlayerControllerImpl.stop` sends: both before the token goes. */
    @Test fun aSwitchEndsTheCast_closesItsSession_andLeavesNothing() {
        onReceiverNow()
        output.stop()
        output.clearMediaItems()
        // At once, not after the Cast framework answered.
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(0, output.mediaItemCount)
        assertEquals(CastActivity.NONE, activities.last())
        idle()
        assertEquals(listOf(true), link.ends)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(0, output.mediaItemCount)
        assertTrue(device.items.isEmpty())
        assertFalse(device.pwr)
        // The session's end arrives too: closed once.
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    @Test fun aSwitchWithNothingLoaded_endsTheCastToo() {
        connect()
        output.stop()
        idle()
        assertEquals(listOf(true), link.ends)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
    }

    @Test fun aStopLeavesTheTitleHere_paused() {
        onReceiverNow()
        playedTo(12_000)
        output.stop()
        idle()
        assertEquals(book, device.items)
        assertEquals(12_000, device.position)
        assertFalse(device.pwr)
    }

    @Test fun release_endsTheCast_andItsSession() {
        onReceiverNow()
        output.release()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertEquals(listOf(true), link.ends)
    }

    // ---- A load that fails.

    @Test fun aFailedLoad_triesTheLocalAddress_thenSaysSoPlainly_pausedHere_atItsPosition() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere()
        connect()
        status(ReceiverActivity.FAILED)
        assertEquals(2, receiver.sent.size)
        assertEquals("http://192.168.1.2:13378/public/session/sess-1/track/1", uris(receiver.sent[1]).first())
        assertEquals(5_000, receiver.sent[1].position)
        assertTrue(said.isEmpty())
        // The same status once more is still the first failure's; only a new one counts.
        output.onReceiverStatus()
        idle()
        assertEquals(2, receiver.sent.size)
        assertTrue(said.isEmpty())

        status(ReceiverActivity.BUSY)
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_unreachable_http), said)
        assertEquals(listOf(true), link.ends)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(book, device.items)
        assertEquals(1, device.index)
        assertEquals(5_000, device.position)
        assertFalse(device.pwr)
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    /** Opening the next session closes the old one on the server, and the receiver may report that just then. */
    @Test fun aFailureLeftFromThePreviousTitle_isNotTheNewLoads() {
        onReceiverNow()
        link.now = ReceiverActivity.FAILED
        link.content = "li#2"
        output.setMediaItems(listOf(localItem("srv", "other", 1), localItem("srv", "other", 2)), 0, 0)
        idle()
        assertEquals(2, receiver.sent.size)
        output.onReceiverStatus()
        idle()
        assertTrue(said.isEmpty())
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
        // Then the new media's own failure counts.
        link.content = "other#1"
        status(ReceiverActivity.BUSY)
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_unreachable), said)
    }

    @Test fun aReceiverThatNeverAnswers_failsTheSameWay() {
        playingHere()
        connect()
        pass(50)
        assertTrue(said.isEmpty())
        pass(11)
        assertEquals(listOf(R.string.cast_unreachable), said)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(5_000, device.position)
        assertFalse(device.pwr)
    }

    /** Minor 7: one limit for a person's wait, shared by the addresses. */
    @Test fun aReceiverThatNeverAnswers_onTwoAddresses_stillEndsWithinAMinute() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere()
        connect()
        pass(39)
        assertEquals("two thirds for the first address", 1, receiver.sent.size)
        pass(2)
        assertEquals(2, receiver.sent.size)
        assertTrue(said.isEmpty())
        pass(20)
        assertEquals(listOf(R.string.cast_unreachable_http), said)
    }

    @Test fun aFormatTheDeviceCannotPlay_isNoAddressProblem() {
        sessions.bases = listOf("https://abs.example".toHttpUrl(), "http://192.168.1.2:13378".toHttpUrl())
        playingHere()
        connect()
        output.onReceiverError(MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED)
        status(ReceiverActivity.FAILED)
        assertEquals(1, receiver.sent.size)
        assertEquals(listOf(R.string.cast_format_failed), said)
    }

    @Test fun aServerThatCannotBeAsked_isSaidSo_andTheTitleWaitsHere() {
        sessions.failure = CastServerOffline()
        playingHere()
        connect()
        assertEquals(listOf(R.string.cast_server_offline), said)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(1, device.index)
        assertEquals(5_000, device.position)
        assertFalse(device.pwr)
    }

    /** Minor 4: no advice that cannot help. */
    @Test fun otherFilesOnTheServer_areNoQueue_andTheMessageSaysSoHonestly() {
        sessions.tracksFor = { 3 }
        playingHere()
        connect()
        assertTrue(receiver.sent.isEmpty())
        assertEquals(listOf(R.string.cast_files_differ), said)
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    @Test fun aFailureWhilePlaying_alsoEndsTheCast_atThePositionItHadReached() {
        onReceiverNow()
        playedTo(33_000)
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_unreachable), said)
        assertEquals(33_000, device.position)
        assertEquals(1, receiver.sent.size)
    }

    @Test fun aTitleWithTooManyFiles_isRefused_beforeAnyLoad_withoutWaiting() {
        val huge = List(2_000) { localItem("srv", "huge", it) }
        sessions.tracksFor = { 2_000 }
        playingHere(huge, 3, 1_000)
        connect()
        assertTrue(receiver.sent.isEmpty())
        assertEquals(listOf(R.string.cast_too_many_files), said)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(3, device.index)
        assertEquals(1_000, device.position)
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    // ---- I3: what the receiver does by itself.

    @Test fun stoppedFromElsewhere_theTitleWaitsPaused_andPlayLoadsItAgain_withANewSession() {
        onReceiverNow()
        playedTo(20_000)
        status(ReceiverActivity.STOPPED)
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        assertEquals(20_000, output.currentPosition)
        assertEquals(1, receiver.sent.size)
        assertEquals(CastActivity.CONNECTED, activities.last())
        link.now = ReceiverActivity.BUSY
        output.play()
        idle()
        assertEquals(2, receiver.sent.size)
        assertEquals(20_000, receiver.sent.last().position)
        assertEquals(2, sessions.opens.size)
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    /** The review's probe: the way back. */
    @Test fun stopped_butTheReceiverPlaysItsOwnTitle_goesBack() {
        onReceiverNow()
        status(ReceiverActivity.STOPPED)
        assertFalse(output.isPlaying)
        link.now = ReceiverActivity.BUSY
        receiver.status(Player.STATE_READY, index = 1, position = 1_000, paused = false)
        idle()
        output.onReceiverStatus()
        idle()
        assertTrue(output.isPlaying)
        assertEquals(1_000, output.currentPosition)
        assertEquals(1, receiver.sent.size)
        assertEquals(CastActivity.PLAYING, activities.last())
    }

    /** Google documents it as interrupted by a load: a queue jump may report it, and playing goes on. */
    @Test fun interrupted_whileTheOwnTitleIsInTheQueue_isBusy() {
        onReceiverNow()
        playedTo(20_000)
        status(ReceiverActivity.INTERRUPTED)
        assertTrue(output.isPlaying)
        assertEquals(1, receiver.sent.size)
        assertFalse(activities.last() == CastActivity.CONNECTED)
    }

    @Test fun interrupted_withAnotherTitleInTheQueue_isStopped() {
        onReceiverNow()
        receiver.items = listOf(MediaItem.fromUri("https://elsewhere.example/a.mp3"))
        receiver.status(Player.STATE_READY, index = 0, position = 0)
        status(ReceiverActivity.INTERRUPTED)
        assertFalse(output.playWhenReady)
        assertEquals(CastActivity.CONNECTED, activities.last())
    }

    /** A moment of an item without a title (a large queue's unknown item, the index fallback) ends nothing. */
    @Test fun aMomentOfAnotherItem_endsNothing_tenSecondsDo() {
        onReceiverNow()
        playedTo(20_000)
        val own = receiver.items
        receiver.items = listOf(MediaItem.fromUri("https://elsewhere.example/a.mp3"))
        receiver.status(Player.STATE_READY, index = 0, position = 0)
        idle()
        assertEquals("the position stands", 20_000, output.currentPosition)
        assertEquals(1, output.currentMediaItemIndex)
        val asked = link.statusRequests
        pass(3)
        assertEquals("asked once", asked, link.statusRequests)
        assertTrue(output.playWhenReady)
        // It was a moment.
        receiver.items = own
        receiver.status(Player.STATE_READY, index = 1, position = 23_000)
        idle()
        assertTrue(output.isPlaying)
        assertEquals(23_000, output.currentPosition)

        // Now for good.
        receiver.items = listOf(MediaItem.fromUri("https://elsewhere.example/a.mp3"))
        receiver.status(Player.STATE_READY, index = 0, position = 0)
        pass(12)
        assertFalse(output.playWhenReady)
        assertEquals(CastActivity.CONNECTED, activities.last())
        assertEquals(23_000, output.currentPosition)
    }

    /** A large queue: the current item is unknown yet, but the status names its content. */
    @Test fun anItemWithoutATitle_butTheOwnContentId_isNoDoubt() {
        onReceiverNow()
        playedTo(20_000)
        link.content = "li#2"
        receiver.items = listOf(MediaItem.EMPTY.buildUpon().setUri("https://x/y").build(), receiver.items[1])
        receiver.status(Player.STATE_READY, index = 0, position = 0)
        pass(20)
        assertTrue(output.playWhenReady)
        assertEquals(20_000, output.currentPosition)
    }

    @Test fun aQueueThatVanishesForAMoment_doesNotTakeTheTitleAway() {
        onReceiverNow()
        playedTo(8_000)
        receiver.queueGone()
        idle()
        assertEquals(2, output.mediaItemCount)
        assertEquals(8_000, output.currentPosition)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        receiver.status(Player.STATE_READY, index = 1, position = 8_500, paused = false)
        idle()
        assertEquals(Player.STATE_READY, output.playbackState)
        assertEquals(1, receiver.sent.size)
    }

    // ---- While it loads.

    @Test fun whatAPersonDoesWhileItLoads_isDoneOnceItIsThere() {
        playingHere()
        connect()
        output.pause()
        output.seekTo(0, 11_000)
        output.setPlaybackSpeed(1.25f)
        idle()
        assertFalse(output.playWhenReady)
        assertEquals(11_000, output.currentPosition)
        assertEquals(1.25f, output.playbackParameters.speed)
        // The load went out before the person's seek: it proves itself with its own start.
        receiver.status(Player.STATE_READY, index = 1, position = 5_000, paused = false)
        idle()
        assertEquals(listOf(0 to 11_000L), receiver.seeks)
        assertFalse(receiver.pwr)
        assertEquals(1.25f, receiver.speed)
        assertEquals(PlaybackParameters(1.25f), output.playbackParameters)
    }

    @Test fun aPauseBeforeTheSessionOpened_isThePausedStart() {
        val slow = CompletableDeferred<Unit>()
        sessions.gate = slow
        playingHere()
        connect()
        output.pause()
        output.seekTo(0, 2_000)
        slow.complete(Unit)
        idle()
        val sent = receiver.sent.single()
        assertFalse(sent.playWhenReady)
        assertEquals(0, sent.index)
        assertEquals(2_000, sent.position)
    }

    @Test fun anItemThatNamesNoTitle_isIgnored() {
        connect()
        output.setMediaItem(MediaItem.fromUri("https://elsewhere.example/a.mp3"))
        idle()
        assertTrue(sessions.opens.isEmpty())
        assertTrue(receiver.sent.isEmpty())
    }

    @Test fun deviceKeysAreRecognised() {
        assertTrue(DeviceLibrary.isLocal(DeviceLibrary.SERVER_ID, deviceItem().titleKey()!!.second))
    }

    // ---- Minor 8: the line under the title says what is true.

    @Test fun theLineFollowsThePhases() {
        playingHere()
        connect()
        assertEquals(CastActivity.PLAYING, activities.last())
        receiver.loaded()
        idle()
        assertEquals(CastActivity.PLAYING, activities.last())
        output.stop()
        assertEquals(CastActivity.NONE, activities.last())
    }
}
