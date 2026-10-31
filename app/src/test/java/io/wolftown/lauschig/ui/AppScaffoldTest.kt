package io.wolftown.lauschig.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.PositionJump
import io.wolftown.lauschig.sync.PositionJumpBus
import io.wolftown.lauschig.ui.common.PositionJumpSnackbar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val ONLINE = ConnectionState.Online("https://abs.example/".toHttpUrl(), ConnectionState.Via.PUBLIC)

private class FakePlayer(initial: PlayerUiState = PlayerUiState()) : PlayerController {
    override val state = MutableStateFlow(initial)
    val seeks = mutableListOf<Int>()
    val plays = mutableListOf<Triple<String, String, Double?>>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { plays += Triple(serverId, itemId, startSec) }
    override fun togglePlayPause() {}
    override fun seekBy(deltaSec: Int) { seeks += deltaSec }
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppScaffoldTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun bottomBar_hasFourItemsWithIconAndLabel() {
        rule.setContent { LauschigTheme { AppScaffold(ONLINE, miniPlayer = {}) { Text("content") } } }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNodeWithText("Library").assertIsDisplayed()
        rule.onNodeWithText("Discover").assertIsDisplayed()
        rule.onNodeWithText("Search").assertIsDisplayed()
        rule.onAllNodesWithTag("navIcon", useUnmergedTree = true).assertCountEquals(4)
    }

    @Test
    fun offlineState_showsBanner_onlineHidesIt() {
        var state by mutableStateOf<ConnectionState>(ConnectionState.Offline)
        rule.mainClock.autoAdvance = false
        rule.setContent { LauschigTheme { AppScaffold(state, miniPlayer = {}) { Text("content") } } }
        rule.onAllNodesWithTag("offlineBanner").assertCountEquals(0)
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS + 100)
        rule.onNodeWithTag("offlineBanner").assertIsDisplayed()
        rule.onNodeWithText("Offline – downloaded titles only").assertIsDisplayed()

        rule.runOnUiThread { state = ONLINE }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onAllNodesWithTag("offlineBanner").assertCountEquals(0)
    }

    @Test
    fun setupRoute_showsNoOfflineBanner_afterDelay() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            LauschigTheme {
                AppScaffold(ConnectionState.Offline, miniPlayer = {}, showBottomBar = false, showOfflineBanner = false) { Text("content") }
            }
        }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS + 100)
        rule.onAllNodesWithTag("offlineBanner").assertCountEquals(0)
    }

    @Test
    fun briefInitialOffline_thenOnline_neverShowsBanner() {
        var state by mutableStateOf<ConnectionState>(ConnectionState.Offline)
        rule.mainClock.autoAdvance = false
        rule.setContent { LauschigTheme { AppScaffold(state, miniPlayer = {}) { Text("content") } } }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS - 1_000)
        rule.onAllNodesWithTag("offlineBanner").assertCountEquals(0)
        rule.runOnUiThread { state = ONLINE }
        rule.mainClock.advanceTimeBy(OFFLINE_BANNER_DELAY_MS * 3)
        rule.onAllNodesWithTag("offlineBanner").assertCountEquals(0)
    }

    @Test
    fun positionJump_showsSnackbar_andUndoSeeksBack() {
        val bus = PositionJumpBus()
        val player = FakePlayer(PlayerUiState(itemId = "book1", positionSec = 4_000.0))
        rule.setContent {
            LauschigTheme {
                val host = remember { SnackbarHostState() }
                AppScaffold(ONLINE, miniPlayer = {}, snackbarHostState = host) { Text("content") }
                PositionJumpSnackbar(bus, player, serverId = "srv", hostState = host)
            }
        }
        bus.emit(PositionJump("other", 10.0, 500.0)) // another item: not shown while book1 is loaded
        bus.emit(PositionJump("book1", fromSec = 3_725.0, toSec = 4_000.0))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continued on another device. Go back to 1:02:05?").assertIsDisplayed()

        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { player.seeks.isNotEmpty() }
        assertEquals(listOf(-275), player.seeks)
        rule.waitUntil(5_000) { bus.pending.value.none { it.itemId == "book1" } }
        assertEquals(listOf("other"), bus.pending.value.map { it.itemId })
    }

    @Test
    fun positionJump_withNoLoadedItem_undoStartsPlaybackAtOldPosition() {
        val bus = PositionJumpBus()
        val player = FakePlayer()
        rule.setContent {
            LauschigTheme {
                val host = remember { SnackbarHostState() }
                AppScaffold(ONLINE, miniPlayer = {}, snackbarHostState = host) { Text("content") }
                PositionJumpSnackbar(bus, player, serverId = "srv", hostState = host)
            }
        }
        bus.emit(PositionJump("book2", fromSec = 60.0, toSec = 900.0))
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { player.plays.isNotEmpty() }
        assertEquals(Triple("srv", "book2", 60.0), player.plays.single())
    }
}
