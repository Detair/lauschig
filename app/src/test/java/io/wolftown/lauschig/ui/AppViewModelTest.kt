package io.wolftown.lauschig.ui

import androidx.work.NetworkType
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.download.DownloadPrompt
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.download.MeteredDownload
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.PositionJumpBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private class PausePlayer(itemId: String) : PlayerController {
        override val state = MutableStateFlow(PlayerUiState(itemId = itemId, isPlaying = true))
        var pauses = 0
        override fun play(serverId: String, itemId: String, startSec: Double?) {}
        override fun togglePlayPause() {}
        override fun pause() { pauses++ }
        override fun seekBy(deltaSec: Int) {}
        override fun seekTo(globalSec: Double) {}
        override fun seekToChapter(index: Int) {}
        override fun setSpeed(speed: Float) {}
    }

    private val enqueued = mutableListOf<String>()
    private val prompts = DownloadPrompts()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(player: PlayerController): AppViewModel {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val manager = object : DownloadManager {
            override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) { enqueued += itemId }
            override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
            override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
        }
        val starter = PlayDownloadStarter(
            flowOf(true), flowOf(MeteredDownload.ASK), {}, { false }, { _, _ -> null }, { _, _ -> null }, manager, prompts, scope,
        )
        val api = ApiProvider(flowOf(ConnectionState.Offline), OkHttpClient(), FakeAccountStore(), scope)
        return AppViewModel(api, FakeAccountStore(), PositionJumpBus(), player, prompts, starter)
    }

    private fun contention(itemId: String) = DownloadPrompt.Contention("s", itemId, "T", 60, NetworkType.CONNECTED)

    @Test fun download_forThePlayingTitle_pausesPlayback() {
        val player = PausePlayer("it")
        vm(player).answerContention(contention("it"), download = true)
        assertEquals(1, player.pauses)
        assertEquals(listOf("it"), enqueued)
    }

    @Test fun download_forAnotherTitle_keepsPlaying() {
        val player = PausePlayer("other")
        vm(player).answerContention(contention("it"), download = true)
        assertEquals(0, player.pauses)
        assertEquals(listOf("it"), enqueued)
    }
}
