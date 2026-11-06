package io.wolftown.lauschig.cast

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
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
 * change of output, and [FakeReceiver] what `RemoteCastPlayer` reports (empty until the receiver's first status).
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CastOutputPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val device = FakeDevice()
    private val receiver = FakeReceiver()
    private val sessions = FakeSessions()
    private val said = ArrayList<Int>()
    private var car = false
    private var now = 1_000_000L
    private val link = FakeLink()
    private lateinit var output: CastOutputPlayer
    private lateinit var cast: FakeCastPlayer
    private val states = ArrayList<Int>()
    private val book = listOf(localItem("srv", "li", 1), localItem("srv", "li", 2))

    @Before fun setUp() {
        output = CastOutputPlayer(device, link, sessions, scope, { said += it }, { car }, clock = { now })
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
    private fun connect() { cast.sessionAvailable(true); idle() }
    private fun disconnect() { cast.sessionAvailable(false); idle() }
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

    // ---- On the device nothing changes.

    @Test fun onTheDevice_everythingGoesThrough_beforeAndAfterTheCastPlayer() {
        val before = CastOutputPlayer(FakeDevice(), link, sessions, scope, { said += it }, { car })
        val other = FakeDevice()
        val direct = CastOutputPlayer(other, link, sessions, scope, { said += it }, { car })
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
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, before.deviceInfo.playbackType)

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
        assertEquals(listOf("https://abs.example/public/session/sess-1/track/1", "https://abs.example/public/session/sess-1/track/2"), sent.items.map { it.localConfiguration!!.uri.toString() })
        assertEquals(1, sent.index)
        assertEquals(5_000, sent.position)
        assertTrue(sent.playWhenReady)
    }

    @Test fun aDownloadedTitle_isCastFromTheServerToo() {
        val files = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "file:///data/x/2.mp3"))
        playingHere(files)
        connect()
        assertTrue(receiver.sent.single().items.all { it.localConfiguration!!.uri.toString().startsWith("https://abs.example/public/session/") })
    }

    /** The hard rule again, at the end of the whole path: every field of every item that reached the receiver. */
    @Test fun nothingBuiltForAReceiverHoldsAToken_orADeviceAddress() {
        val files = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "https://abs.example/api/items/li/file/2/download?token=secret#srv"))
        playingHere(files)
        connect()
        receiver.sent.single().items.forEach { item ->
            val json = DefaultMediaItemConverter().toMediaQueueItem(item).toJson().toString()
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
        receiver.position = 6_000
        receiver.loaded()
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
        output.setMediaItems(book, 0, 2_000)
        output.prepare()
        output.play()
        idle()
        assertEquals(listOf("srv" to "li"), sessions.opens)
        assertEquals(2_000, receiver.sent.single().position)
        assertTrue(output.playWhenReady)
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

    // ---- Back to the device.

    @Test fun takingItBack_keepsThePosition_withTheDevicesOwnItems_paused() {
        onReceiverNow()
        receiver.index = 1
        receiver.position = 42_000
        disconnect()
        assertEquals(book, device.items)
        assertEquals(1, device.index)
        assertEquals(42_000, device.position)
        assertFalse(device.pwr)
        assertTrue(device.prepared >= 2)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(42_000, output.currentPosition)
        assertTrue(device.items.none(ReceiverItems::isReceiverItem))
    }

    @Test fun theSpeedAPersonChose_comesBackWithTheTitle_alsoAboveTheReceiversLimit() {
        val asked = ArrayList<Float>()
        output.onSpeedRequested = { asked += it }
        onReceiverNow()
        output.setPlaybackSpeed(2.5f)
        idle()
        assertEquals(listOf(2.5f), asked)
        assertEquals(2f, receiver.speed)
        disconnect()
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

    @Test fun thePhonesOutputSwitcher_continuesAPlayingTitleHere() {
        onReceiverNow()
        disconnect()
        assertFalse(device.pwr)
        output.onCastTakenBack()
        assertTrue(device.pwr)
    }

    @Test fun thePhonesOutputSwitcher_doesNotStartAPausedTitle_orOneFromLongAgo() {
        onReceiverNow()
        output.pause()
        idle()
        disconnect()
        output.onCastTakenBack()
        assertFalse(device.pwr)

        output.play()
        idle()
        connect()
        receiver.loaded()
        idle()
        disconnect()
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
        disconnect()
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
        assertEquals("https://abs.example/public/session/sess-2/track/1", receiver.sent.last().items.first().localConfiguration!!.uri.toString())
        assertEquals(1_000, receiver.sent.last().position)
    }

    @Test fun aSessionThatOpensTooLate_isClosedAgain() {
        val slow = CompletableDeferred<Unit>()
        sessions.gate = slow
        playingHere()
        connect()
        disconnect()
        slow.complete(Unit)
        idle()
        assertEquals(listOf("sess-1"), sessions.closed)
        assertTrue(receiver.sent.isEmpty())
    }

    @Test fun theSameTitleWithNewSources_reachesOnlyTheWayBack() {
        onReceiverNow()
        val downloaded = listOf(localItem("srv", "li", 1, "file:///data/x/1.mp3"), localItem("srv", "li", 2, "file:///data/x/2.mp3"))
        output.setMediaItems(downloaded, receiver.index, receiver.position)
        idle()
        assertEquals(1, sessions.opens.size)
        assertEquals(1, receiver.sent.size)
        assertTrue(receiver.seeks.isEmpty())
        disconnect()
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
        disconnect()
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

    /** A suspended Cast session (a network hiccup) may come back: the session on the server waits for it. */
    @Test fun aSuspendedSession_keepsTheServerSession_andUsesItAgain() {
        onReceiverNow()
        receiver.position = 9_000
        disconnect()
        assertTrue(sessions.closed.isEmpty())
        connect()
        assertEquals(1, sessions.opens.size)
        assertEquals("https://abs.example/public/session/sess-1/track/1", receiver.sent.last().items.first().localConfiguration!!.uri.toString())
        assertEquals(9_000, receiver.sent.last().position)
    }

    @Test fun anEndedSession_closesTheServerSession() {
        onReceiverNow()
        disconnect()
        output.onCastSessionEnded()
        assertEquals(listOf("sess-1"), sessions.closed)
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
        receiver.position = 12_000
        // Remembered while it plays (once a second), read when it ends.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
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
        assertEquals("http://192.168.1.2:13378/public/session/sess-1/track/1", receiver.sent[1].items.first().localConfiguration!!.uri.toString())
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
        output.setMediaItems(listOf(localItem("srv", "other", 1), localItem("srv", "other", 2)), 0, 0)
        idle()
        assertEquals(2, receiver.sent.size)
        output.onReceiverStatus()
        idle()
        assertTrue(said.isEmpty())
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, output.deviceInfo.playbackType)
        // Then the new media's own failure counts.
        status(ReceiverActivity.BUSY)
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_unreachable), said)
    }

    @Test fun aReceiverThatNeverAnswers_failsTheSameWay() {
        playingHere()
        connect()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(50))
        assertEquals(listOf(R.string.cast_unreachable), said)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, output.deviceInfo.playbackType)
        assertEquals(5_000, device.position)
        assertFalse(device.pwr)
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

    @Test fun otherFilesOnTheServer_areNoQueue() {
        sessions.tracksFor = { 3 }
        playingHere()
        connect()
        assertTrue(receiver.sent.isEmpty())
        assertEquals(listOf(R.string.cast_open_failed), said)
        assertEquals(listOf("sess-1"), sessions.closed)
    }

    @Test fun aFailureWhilePlaying_alsoEndsTheCast_atThePositionItHadReached() {
        onReceiverNow()
        receiver.position = 33_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        status(ReceiverActivity.FAILED)
        assertEquals(listOf(R.string.cast_unreachable), said)
        assertEquals(33_000, device.position)
        assertEquals(1, receiver.sent.size)
    }

    // ---- The end, and what happens on the receiver by itself.

    @Test fun theReceiverFinishing_isTheEndOfTheTitle_forTheProgressAndTheQueue() {
        playingHere(index = 1)
        connect()
        receiver.loaded()
        idle()
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_ENDED, output.playbackState)
        assertEquals(Player.STATE_ENDED, states.last())
        assertEquals(2, output.mediaItemCount)
        assertEquals("srv" to "li", output.currentMediaItem!!.titleKey())
        assertEquals(1, output.currentMediaItemIndex)
        assertEquals(100_000, output.currentPosition)

        // The queue's next title goes where the sound is.
        val next = listOf(localItem("srv", "next", 1), localItem("srv", "next", 2))
        output.setMediaItems(next, 0, 0)
        output.prepare()
        output.play()
        idle()
        assertEquals("srv" to "next", sessions.opens.last())
        assertEquals("https://abs.example/public/session/sess-2/track/1", receiver.sent.last().items.first().localConfiguration!!.uri.toString())
    }

    @Test fun aFinishedFileInTheMiddle_isNoEnd() {
        playingHere(index = 0)
        connect()
        receiver.loaded()
        idle()
        status(ReceiverActivity.FINISHED)
        assertEquals(Player.STATE_READY, output.playbackState)
    }

    @Test fun aSeekAfterTheEnd_loadsAgain_fromTheSameSession() {
        onReceiverNow()
        status(ReceiverActivity.FINISHED)
        output.seekTo(0, 1_000)
        idle()
        assertEquals(1, sessions.opens.size)
        assertEquals(2, receiver.sent.size)
        assertEquals(1_000, receiver.sent.last().position)
    }

    @Test fun stoppedFromElsewhere_theTitleWaitsPaused_andPlayLoadsItAgain() {
        onReceiverNow()
        receiver.position = 20_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        status(ReceiverActivity.STOPPED)
        assertEquals(Player.STATE_READY, output.playbackState)
        assertFalse(output.playWhenReady)
        assertEquals(20_000, output.currentPosition)
        assertEquals(1, receiver.sent.size)
        link.now = ReceiverActivity.BUSY
        output.play()
        idle()
        assertEquals(2, receiver.sent.size)
        assertEquals(20_000, receiver.sent.last().position)
    }

    @Test fun aQueueThatVanishesForAMoment_doesNotTakeTheTitleAway() {
        onReceiverNow()
        receiver.position = 8_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        receiver.queueGone()
        idle()
        assertEquals(2, output.mediaItemCount)
        assertEquals(8_000, output.currentPosition)
        assertEquals(Player.STATE_BUFFERING, output.playbackState)
        receiver.loaded()
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
        receiver.pwr = true
        receiver.loaded()
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
}
