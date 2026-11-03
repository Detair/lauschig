package io.wolftown.lauschig.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.window.core.layout.WindowSizeClass
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.ItemDetail
import io.wolftown.lauschig.ui.nav.Library
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.Start
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h800dp")
class ListDetailPaneTest {
    @get:Rule val rule = createComposeRule()

    private var twoPane by mutableStateOf(true)
    private lateinit var outer: NavHostController

    /** [start]: where the outer stack begins; [setContent] swaps in a [StateRestorationTester] to emulate a recreation. */
    private fun show(start: Any = Library, setContent: (@Composable () -> Unit) -> Unit = rule::setContent) = setContent {
        LauschigTheme {
            outer = rememberNavController()
            val wide = calculatePaneScaffoldDirective(WindowAdaptiveInfo(WindowSizeClass.compute(1280f, 800f), Posture()))
            AdoptOuterPageOnGrow(outer, twoPane)
            NavHost(outer, startDestination = start) {
                composable<Start> {
                    Button(onClick = { outer.navigate(ItemDetail("x")) }) { Text("open x from start") }
                }
                composable<Library> {
                    ListDetailPane(
                        outer,
                        directive = wide,
                        twoPane = twoPane,
                        list = { open ->
                            Column {
                                Text("the list")
                                Button(onClick = { open.openItem("a") }) { Text("open a") }
                                Button(onClick = { open.openItem("b") }) { Text("open b") }
                                Button(onClick = { open.openBrowse(BrowseKind.AUTHOR, "Ann") }) { Text("open Ann") }
                            }
                        },
                        paneGraph = { pane ->
                            composable<ItemDetail> { e ->
                                Column {
                                    Text("pane:${e.toRoute<ItemDetail>().itemId}")
                                    Button(onClick = { pane.navigate(ItemDetail("next")) }) { Text("link") }
                                    Button(onClick = { pane.popBackStack() }) { Text("pane back") }
                                }
                            }
                            composable<Browse> { e -> Text("pane browse:${e.toRoute<Browse>().name}") }
                        },
                    )
                }
                composable<ItemDetail> { e -> Text("outer:${e.toRoute<ItemDetail>().itemId}") }
                composable<Browse> { e -> Text("outer browse:${e.toRoute<Browse>().name}") }
            }
        }
    }

    @Test fun wide_detailsOpenBesideTheList() {
        show()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed()
        rule.onNodeWithText("open a").performClick()
        rule.onNodeWithText("pane:a").assertIsDisplayed()
        rule.onNodeWithText("the list").assertIsDisplayed()
        rule.onNodeWithText("open Ann").performClick()
        rule.onNodeWithText("pane browse:Ann").assertIsDisplayed()
    }

    @Test fun wide_theListReplacesThePane_linksInsidePush() {
        show()
        rule.onNodeWithText("open a").performClick()
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("link").performClick()
        rule.onNodeWithText("pane:next").assertIsDisplayed()
        rule.onNodeWithText("pane back").performClick()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
        rule.onNodeWithText("pane back").performClick()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed() // not "a": the list replaced it
    }

    @Test fun onePane_opensOnTheOuterStack() {
        twoPane = false
        show()
        rule.onNodeWithTag("detailPane").assertDoesNotExist()
        rule.onNodeWithText("open a").performClick()
        rule.onNodeWithText("outer:a").assertIsDisplayed()
    }

    @Test fun shrinkingToOnePane_movesThePanePageOntoTheOuterStack() {
        show()
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
        twoPane = false
        rule.waitForIdle()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
    }

    private fun onOuterTop(block: (NavHostController) -> Unit) = rule.runOnIdle { block(outer) }

    @Test fun growingToTwoPanes_movesTheOuterDetailIntoThePane() {
        twoPane = false
        show()
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
        rule.onNodeWithText("the list").assertIsDisplayed()
        rule.onNodeWithText("outer:b").assertDoesNotExist()
        // The page left the outer stack: no double entry, "back" has nothing of it left to return to.
        onOuterTop { assertTrue(it.currentBackStackEntry!!.destination.hasRoute<Library>()) }
    }

    @Test fun growingToTwoPanes_movesAnEntityPageToo() {
        twoPane = false
        show()
        rule.onNodeWithText("open Ann").performClick()
        rule.onNodeWithText("outer browse:Ann").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("pane browse:Ann").assertIsDisplayed()
        rule.onNodeWithText("outer browse:Ann").assertDoesNotExist()
    }

    @Test fun roundTrip_keepsTheTitle_andBackReturnsToTheList() {
        twoPane = false
        show()
        rule.onNodeWithText("open a").performClick()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("pane:a").assertIsDisplayed()
        twoPane = false
        rule.waitForIdle()
        rule.onNodeWithText("outer:a").assertIsDisplayed()
        onOuterTop {
            assertTrue(it.currentBackStackEntry!!.destination.hasRoute<ItemDetail>())
            assertTrue(it.previousBackStackEntry!!.destination.hasRoute<Library>())
            it.popBackStack()
        }
        rule.waitForIdle()
        rule.onNodeWithText("the list").assertIsDisplayed()
        rule.onNodeWithText("outer:a").assertDoesNotExist()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed() // backed out of it: nothing left to bring back
    }

    @Test fun growing_leavesPagesAlone_thatDidNotComeFromAList() {
        twoPane = false
        show(start = Start)
        rule.onNodeWithText("open x from start").performClick()
        rule.onNodeWithText("outer:x").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("outer:x").assertIsDisplayed()
    }

    @Test fun alreadyWide_aPageOpenedOnTheOuterStackStaysThere() {
        show()
        rule.onNodeWithText("the list").assertIsDisplayed()
        onOuterTop { it.navigate(ItemDetail("z")) } // a link, a notification, "Details anzeigen" of the player
        rule.waitForIdle()
        rule.onNodeWithText("outer:z").assertIsDisplayed()
        rule.onNodeWithTag("detailPane").assertDoesNotExist()
    }

    @Test fun rotation_recreatesTheActivity_theMoveHappensOnTheRecreatedOne() {
        val restoration = StateRestorationTester(rule)
        twoPane = false
        show(setContent = restoration::setContent)
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
        twoPane = true
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
        rule.onNodeWithText("outer:b").assertDoesNotExist()
        // ... and a recreation that keeps the wide window does not touch the pane.
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
    }
}
