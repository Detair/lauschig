package io.wolftown.lauschig.ui.player

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.playback.ShakeSource
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class IdlePlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override fun play(serverId: String, itemId: String, startSec: Double?) {}
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComfortViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { PlaybackSettings(PreferenceDataStoreFactory.create(scope = io) { tmp.newFile("playback.preferences_pb") }) }
    private val db by lazy {
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    }
    private val viewModels = TestViewModels()
    private val player = IdlePlayer()
    private val shakes = object : ShakeSource {
        override fun start(onShake: () -> Unit) {}
        override fun stop() {}
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() {
        viewModels.clear()
        db.close()
        io.cancel()
        Dispatchers.resetMain()
    }

    private fun vm() = viewModels.own(
        ComfortViewModel(SleepTimer(player, shakes, CoroutineScope(UnconfinedTestDispatcher())), settings, db.titleAudioDao(), FakeAccountStore(), player),
    )

    /** DataStore answers on its own thread: wait in real time, not in the test's virtual time. */
    private suspend fun <T> soon(block: suspend () -> T): T = withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { block() } }

    @Test fun compressor_offByDefault_theSwitchStoresIt() = runTest {
        val vm = vm()
        assertFalse(soon { settings.speechCompressor.first() })
        vm.setCompressor(true)
        soon { vm.state.first { it.compressor } }
        assertEquals(true, settings.speechCompressor.first())
    }

    @Test fun sleepChime_onByDefault_theSwitchStoresIt() = runTest {
        val vm = vm()
        soon { vm.state.first { it.sleepChime } }
        vm.setSleepChime(false)
        soon { vm.state.first { !it.sleepChime } }
        assertEquals(false, settings.sleepChime.first())
    }
}
