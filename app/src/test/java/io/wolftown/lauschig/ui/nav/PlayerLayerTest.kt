package io.wolftown.lauschig.ui.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player layer as AppRoot wires it: [rememberPlayerLayer] over a NavHost with the Player destination, [PlayerOverlay]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerLayerTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private var layer: PlayerLayer? = null

    /** Whether the layer was shown, per composition while [recording] (a slide replay starts from "not shown"). */
    private var recording = false
    private val shownPerComposition = mutableListOf<Boolean>()

    private fun frameWithLayer(tester: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            LauschigTheme {
                nav = rememberNavController()
                val l = rememberPlayerLayer(nav)
                layer = l
                if (recording) shownPerComposition += l.slide.currentState
                Box(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = Start) {
                        composable<Start> { Text("start page") }
                        composable<Player> { Box(Modifier.fillMaxSize()) }
                    }
                    PlayerOverlay(l.slide, banner = null, showReauthBanner = false, onReauth = {}, onOpenConnection = {}, snackbarHostState = SnackbarHostState(), onClose = { nav.popBackStack() }) {
                        Text("the player", Modifier.testTag("playerContent"))
                    }
                }
            }
        }
        if (tester != null) tester.setContent(content) else rule.setContent(content)
    }

    @Test fun recreatedWithThePlayerOpen_showsItAtOnce_noSlideReplayed() {
        val tester = StateRestorationTester(rule)
        frameWithLayer(tester)
        rule.runOnUiThread { nav.navigate(Player) }
        rule.waitForIdle()
        assertTrue(layer!!.coversFrame)

        // Rotation, theme change, process restore: the activity's content is composed anew from saved state.
        recording = true
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertTrue(shownPerComposition.isNotEmpty())
        assertTrue("shown from the first composition on, never slid in again: $shownPerComposition", shownPerComposition.all { it })
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val overlay = rule.onNodeWithTag("playerOverlay").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(root.top, overlay.top, 0.5f)
        assertTrue("the frame is covered once the back stack is back", layer!!.coversFrame)
        rule.onNodeWithText("the player").assertIsDisplayed()
    }

    @Test fun recreatedWithoutThePlayer_staysClosed() {
        val tester = StateRestorationTester(rule)
        frameWithLayer(tester)
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("playerOverlay").assertDoesNotExist()
        rule.onNodeWithText("start page").assertIsDisplayed()
    }

    @Test fun opening_stillSlides_andBackClosesTheLayer() {
        frameWithLayer()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.navigate(Player) }
        rule.mainClock.advanceTimeBy(PLAYER_SLIDE_MS / 3L)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val mid = rule.onNodeWithTag("playerOverlay").fetchSemanticsNode().boundsInRoot
        assertTrue("mid-slide the layer is still below the top", mid.top > root.top + 1f)
        assertFalse(layer!!.coversFrame)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(layer!!.coversFrame)
        rule.runOnUiThread { nav.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithTag("playerOverlay").assertDoesNotExist()
        assertFalse(layer!!.up)
    }

    @Test fun underACoveringPlayer_keyboardFocusDoesNotEnterTheFrame() {
        lateinit var focus: FocusManager
        rule.setContent {
            focus = LocalFocusManager.current
            Column {
                Box(Modifier.underPlayer(covered = true)) {
                    Box(Modifier.size(10.dp).testTag("frameControl").focusable())
                }
                Box(Modifier.size(10.dp).testTag("playerControl").focusable())
            }
        }
        repeat(3) {
            rule.runOnUiThread { focus.moveFocus(FocusDirection.Next) }
            rule.onNodeWithTag("frameControl", useUnmergedTree = true).assertIsNotFocused()
        }
        rule.onNodeWithTag("playerControl").assertIsFocused()
    }

    @Test fun notCovered_theFrameIsReachableAsUsual() {
        lateinit var focus: FocusManager
        rule.setContent {
            focus = LocalFocusManager.current
            Box(Modifier.underPlayer(covered = false)) { Box(Modifier.size(10.dp).testTag("frameControl").focusable()) }
        }
        rule.runOnUiThread { focus.moveFocus(FocusDirection.Next) }
        rule.onNodeWithTag("frameControl").assertIsFocused()
    }
}
