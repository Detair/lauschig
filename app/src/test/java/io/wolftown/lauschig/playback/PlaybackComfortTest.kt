package io.wolftown.lauschig.playback

import android.app.Application
import android.os.Looper
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import io.wolftown.lauschig.cast.FakeDevice
import io.wolftown.lauschig.cast.localItem
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The split of Phase 7: the title's parts on `output.player`, the sound's parts on `output.device`. [Output] is a player on top
 * of another than the device, as on a Cast receiver; its device is a real ExoPlayer that nothing should touch but the sound's parts.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackComfortTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { PlaybackSettings(PreferenceDataStoreFactory.create(scope = io) { tmp.newFile("playback.preferences_pb") }) }
    private val db = Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val exo = ExoPlayer.Builder(context).build()
    private val here = FakeDevice()
    private var clock = 1_000_000L
    private val activity = ArrayList<String>()

    /** Plays on [here] and reports a device type a test can change. */
    private inner class Output : ForwardingSimpleBasePlayer(here) {
        var info: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
            set(value) {
                field = value
                invalidateState()
            }

        override fun getState(): State = super.getState().buildUpon().setDeviceInfo(info).build()
        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            requested += playbackParameters.speed
            return super.handleSetPlaybackParameters(playbackParameters)
        }
        fun changed() = invalidateState()
    }

    private val requested = ArrayList<Float>()
    private val output by lazy { Output() }
    private val comfort by lazy {
        PlaybackComfort(output, exo, db.titleAudioDao(), settings, scope, io, onTitleActivity = { s, i -> activity += "$s:$i" }, clock = { clock })
    }

    @After fun tearDown() {
        comfort.release()
        scope.cancel()
        exo.release()
        db.close()
        io.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** DataStore and Room answer on their own threads. */
    private fun settle() = repeat(30) {
        idle()
        Thread.sleep(10)
    }

    private fun start(key: String = "li") {
        comfort.start()
        settle()
        output.setMediaItems(listOf(localItem("srv", key, 1), localItem("srv", key, 2)), 0, 30_000)
        output.prepare()
        output.play()
        settle()
    }

    private fun onReceiver() {
        output.info = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build()
        idle()
    }

    private fun storedSpeed() = runBlocking { db.titleAudioDao().observe("srv", "li").first()?.speed }

    @Test fun theTitlesOwnSpeed_goesToThePlayer_notToTheDevice() {
        runBlocking { db.titleAudioDao().setSpeed("srv", "li", 1.5f) }
        start()
        assertEquals(1.5f, here.speed)
        assertEquals(1f, exo.playbackParameters.speed)
    }

    @Test fun skippingSilence_staysOnTheDevice() {
        runBlocking { settings.setSkipSilence(true) }
        start()
        assertTrue(exo.skipSilenceEnabled)
    }

    @Test fun aPersonsSpeed_onThisDevice_isStoredForTheTitle() {
        start()
        output.setPlaybackSpeed(1.25f)
        settleSave()
        assertEquals(1.25f, storedSpeed())
    }

    @Test fun whatAReceiverReports_isNotAPersonsChoice() {
        start()
        onReceiver()
        // After every load 1×; a clamped 2× for a person's 2.5×.
        here.speed = 1.75f
        output.changed()
        settleSave()
        assertNull(storedSpeed())
    }

    @Test fun aPersonsSpeedOnAReceiver_isTheTitles_alsoAboveWhatTheReceiverCanDo() {
        start()
        onReceiver()
        comfort.speedRequested(2.5f)
        settleSave()
        assertEquals(2.5f, storedSpeed())
    }

    @Test fun aSpeedTheMemoryApplied_isNotStored_alsoOnAReceiver() {
        runBlocking { settings.setDefaultSpeed(1.5f) }
        start()
        onReceiver()
        comfort.speedRequested(1.5f)
        settleSave()
        assertNull(storedSpeed())
    }

    @Test fun anotherTitleOnAReceiver_startsAtItsOwnSpeed_throughThePlayer() {
        runBlocking {
            db.titleAudioDao().setSpeed("srv", "other", 1.75f)
        }
        start()
        onReceiver()
        requested.clear()
        output.setMediaItems(listOf(localItem("srv", "other", 1)), 0, 0)
        settle()
        assertEquals(1.75f, requested.last())
        assertEquals(1f, exo.playbackParameters.speed)
    }

    @Test fun rewindingAfterAPause_happensOnThePlayer() {
        runBlocking { settings.setRewindOnResumeSec(10) }
        start()
        output.pause()
        idle()
        clock += 60_000
        here.seeks.clear()
        output.play()
        idle()
        assertEquals(listOf(0 to 20_000L), here.seeks)
    }

    /** The move to a receiver is no pause of the person's: no jump back on arrival. */
    @Test fun movingToAnotherOutput_doesNotRewind() {
        runBlocking { settings.setRewindOnResumeSec(10) }
        start()
        output.pause()
        idle()
        onReceiver()
        clock += 60_000
        here.seeks.clear()
        output.play()
        idle()
        assertTrue(here.seeks.isEmpty())
    }

    @Test fun theLastActivity_followsTheTitle_onAReceiverToo() {
        start()
        onReceiver()
        activity.clear()
        output.pause()
        idle()
        assertEquals(listOf("srv:li"), activity)
    }

    @Test fun theDevicesPlayerIsNotStarted() {
        start()
        onReceiver()
        assertFalse(exo.playWhenReady)
        assertEquals(Player.STATE_IDLE, exo.playbackState)
        assertEquals(0, exo.mediaItemCount)
    }

    private fun settleSave() {
        settle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        settle()
    }

}
