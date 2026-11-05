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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.ui.AppScaffold
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.nav.Browse
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.nav.Discover
import io.wolftown.lauschig.ui.nav.ItemDetail
import io.wolftown.lauschig.ui.nav.Library
import io.wolftown.lauschig.ui.nav.Player
import io.wolftown.lauschig.ui.nav.Search
import io.wolftown.lauschig.ui.nav.Start
import io.wolftown.lauschig.ui.nav.navigateToTab
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the window offers: two panes (>= 840 dp wide) and, from 600 dp, the navigation rail instead of the bottom bar. */
private class Window(val twoPane: Boolean, val rail: Boolean)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h800dp")
class ListDetailPaneTest {
    @get:Rule val rule = createComposeRule()

    private var live by mutableStateOf(Window(twoPane = true, rail = true))

    /**
     * Recreation tests set this instead of [live]: a plain field, so changing it recomposes nothing and only the
     * recreated composition reads the new window (like an activity that is destroyed and created for the new size).
     */
    private var frozen: Window? = null
    private val window: Window get() = frozen ?: live

    private var twoPane: Boolean
        get() = live.twoPane
        set(v) { live = Window(v, live.rail) }

    private lateinit var outer: NavHostController

    /** [start]: where the outer stack begins; [setContent] swaps in a [StateRestorationTester] to emulate a recreation. */
    private fun show(start: Any = Library, setContent: (@Composable () -> Unit) -> Unit = rule::setContent) = setContent {
        LauschigTheme {
            outer = rememberNavController()
            val w = window
            val wide = calculatePaneScaffoldDirective(WindowAdaptiveInfo(WindowSizeClass.compute(1280f, 800f), Posture()))
            // Like the app: the content sits in a different part of the tree with and without the rail.
            AppScaffold(ONLINE, miniPlayer = {}, navigationRail = w.rail) {
                AdoptOuterPageIntoPane(outer, w.twoPane)
                NavHost(outer, startDestination = start) {
                    composable<Start> {
                        Button(onClick = { outer.navigate(ItemDetail("x")) }) { Text("open x from start") }
                    }
                    composable<Discover> { Text("discover") }
                    composable<Player> { Text("player") }
                    composable<Library> { host ->
                        ListDetailPane(
                            outer, host,
                            directive = wide,
                            twoPane = w.twoPane,
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
                    composable<Search> { host ->
                        ListDetailPane(
                            outer, host,
                            directive = wide,
                            twoPane = w.twoPane,
                            list = { open -> Column { Text("the search"); Button(onClick = { open.openItem("s") }) { Text("open s") } } },
                            paneGraph = { composable<ItemDetail> { e -> Text("pane:${e.toRoute<ItemDetail>().itemId}") } },
                        )
                    }
                    composable<ItemDetail> { e -> Text("outer:${e.toRoute<ItemDetail>().itemId}") }
                    composable<Browse> { e -> Text("outer browse:${e.toRoute<Browse>().name}") }
                }
            }
        }
    }

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** The title is shown exactly once: in the pane or as an outer page. */
    private fun assertShownOnce(inPane: Boolean, id: String = "b") {
        assertEquals("pane:$id", if (inPane) 1 else 0, count("pane:$id"))
        assertEquals("outer:$id", if (inPane) 0 else 1, count("outer:$id"))
    }

    private fun onOuter(block: (NavHostController) -> Unit) = rule.runOnIdle { block(outer) }

    /** Opens "b" from the list in [first], then recreates the activity for [second] (nothing recomposes before). */
    private fun recreate(first: Window, second: Window) {
        val restoration = StateRestorationTester(rule)
        frozen = first
        show(setContent = restoration::setContent)
        rule.onNodeWithText("open b").performClick()
        assertShownOnce(inPane = first.twoPane)
        frozen = second
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
    }

    // --- the panes themselves

    @Test fun wide_detailsOpenBesideTheList() {
        show()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed()
        rule.onNodeWithText("open a").performClick()
        rule.onNodeWithText("pane:a").assertIsDisplayed()
        rule.onNodeWithText("the list").assertIsDisplayed()
        rule.onNodeWithText("open Ann").performClick()
        rule.onNodeWithText("pane browse:Ann").assertIsDisplayed()
    }

    // Fix round 1, E8: re-tapping the tab at its root closes the pane's page(s) – the plain list, as on one pane.
    @Test fun wide_reTappingTheTab_closesThePane() {
        show()
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("link").performClick()
        rule.onNodeWithText("pane:next").assertIsDisplayed()
        onOuter { it.navigateToTab(TopLevelTab.Library) }
        rule.waitForIdle()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed()
        rule.onNodeWithText("the list").assertIsDisplayed()
        assertEquals(0, count("pane:b") + count("pane:next"))
        // The list opens titles as before, and a page closed this way never comes back on one pane.
        rule.onNodeWithText("open a").performClick()
        rule.onNodeWithText("pane:a").assertIsDisplayed()
        onOuter { it.navigateToTab(TopLevelTab.Library) }
        rule.waitForIdle()
        twoPane = false
        rule.waitForIdle()
        rule.onNodeWithText("the list").assertIsDisplayed()
        assertEquals(0, count("outer:a"))
    }

    @Test fun onePane_reTappingTheTab_keepsTheNextWidePaneWorking() {
        twoPane = false
        show()
        onOuter { it.navigateToTab(TopLevelTab.Library) } // at the root: a close request with no pane
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
        twoPane = true // the outer page moves into the pane – the old request must not close it
        rule.waitForIdle()
        assertShownOnce(inPane = true)
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

    // --- shrinking: the pane's page moves onto the outer stack

    @Test fun shrinkingToOnePane_movesThePanePageOntoTheOuterStack() {
        show()
        rule.onNodeWithText("open b").performClick()
        rule.onNodeWithText("pane:b").assertIsDisplayed()
        twoPane = false
        rule.waitForIdle()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
    }

    // --- growing: an outer page directly above a host moves into the pane, whenever that becomes true

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
        // The page left the outer stack: no double entry, the library is what is on top.
        onOuter { assertTrue(it.currentBackStackEntry!!.destination.hasRoute<Library>()) }
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

    @Test fun growingToTwoPanes_searchIsAHostToo() {
        twoPane = false
        show(start = Search)
        rule.onNodeWithText("open s").performClick()
        rule.onNodeWithText("outer:s").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("pane:s").assertIsDisplayed()
        rule.onNodeWithText("the search").assertIsDisplayed()
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
        onOuter {
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

    @Test fun flippingTheWindowAgainAndAgain_neverLoopsOrDuplicates() {
        twoPane = false
        show()
        rule.onNodeWithText("open b").performClick()
        for (wide in listOf(true, false, true, false, true)) {
            twoPane = wide
            rule.waitForIdle()
            assertShownOnce(inPane = wide)
        }
        twoPane = false
        rule.waitForIdle()
        assertShownOnce(inPane = false)
        onOuter { it.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("the list").assertIsDisplayed()
        assertEquals("nothing of the page is left", 0, count("outer:b") + count("pane:b"))
    }

    @Test fun whileWide_aPageThatLandsOnTheOuterStack_movesIntoThePane() {
        show()
        rule.onNodeWithText("the list").assertIsDisplayed()
        onOuter { it.navigate(ItemDetail("z")) } // a link or a notification
        rule.waitForIdle()
        rule.onNodeWithText("pane:z").assertIsDisplayed()
        rule.onNodeWithText("outer:z").assertDoesNotExist()
    }

    @Test fun whileWide_detailsFromThePlayer_openInThePane() {
        show()
        onOuter { it.navigate(Player) }
        rule.waitForIdle()
        rule.onNodeWithText("player").assertIsDisplayed()
        // "Details anzeigen": the details replace the player, as in AppNavHost.
        onOuter { it.navigate(ItemDetail("z")) { popUpTo<Player> { inclusive = true } } }
        rule.waitForIdle()
        rule.onNodeWithText("pane:z").assertIsDisplayed()
        rule.onNodeWithText("the list").assertIsDisplayed()
    }

    @Test fun playerOpenDuringTheRotation_thePageMovesWhenThePlayerCloses() {
        twoPane = false
        show()
        rule.onNodeWithText("open b").performClick()
        onOuter { it.navigate(Player) }
        rule.waitForIdle()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("player").assertIsDisplayed()
        onOuter { it.popBackStack() }
        rule.waitForIdle()
        assertShownOnce(inPane = true)
        rule.onNodeWithText("the list").assertIsDisplayed()
    }

    @Test fun anotherTabDuringTheRotation_thePageMovesWhenTheTabComesBack() {
        twoPane = false
        show(start = Start)
        onOuter { it.navigateToTab(TopLevelTab.Library) }
        rule.waitForIdle()
        rule.onNodeWithText("open b").performClick()
        onOuter { it.navigateToTab(TopLevelTab.Discover) } // parks [Library, ItemDetail]
        rule.waitForIdle()
        rule.onNodeWithText("discover").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("discover").assertIsDisplayed()
        onOuter { it.navigateToTab(TopLevelTab.Library) } // restores it
        rule.waitForIdle()
        assertShownOnce(inPane = true)
    }

    // --- what never moves

    @Test fun pagesAboveStart_neverMove_inEitherWindow() {
        twoPane = false
        show(start = Start)
        rule.onNodeWithText("open x from start").performClick()
        rule.onNodeWithText("outer:x").assertIsDisplayed()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("outer:x").assertIsDisplayed()
        twoPane = false
        rule.waitForIdle()
        rule.onNodeWithText("outer:x").assertIsDisplayed()
    }

    @Test fun onlyThePageDirectlyAboveTheHostMoves_theOneBelowFollowsWhenItIsOnTop() {
        twoPane = false
        show()
        rule.onNodeWithText("open a").performClick()
        onOuter { it.navigate(ItemDetail("b")) } // a link inside the page: [Library, a, b]
        rule.waitForIdle()
        twoPane = true
        rule.waitForIdle()
        rule.onNodeWithText("outer:b").assertIsDisplayed()
        onOuter { it.popBackStack() } // "back" from b shows a, which is now directly above the host
        rule.waitForIdle()
        assertShownOnce(inPane = true, id = "a")
    }

    // --- recreation (rotation destroys the activity): nothing recomposes before, the new window is read by the new composition

    @Test fun recreation_growing_theOuterPageMovesIntoThePane() {
        recreate(Window(twoPane = false, rail = true), Window(twoPane = true, rail = true))
        assertShownOnce(inPane = true)
    }

    // A large phone: bottom bar in portrait, rail and two panes in landscape - the content sits elsewhere in the tree.
    @Test fun recreation_growing_whileTheRailAppears_theOuterPageMovesIntoThePane() {
        recreate(Window(twoPane = false, rail = false), Window(twoPane = true, rail = true))
        assertShownOnce(inPane = true)
        rule.onNodeWithText("the list").assertIsDisplayed()
    }

    @Test fun recreation_shrinking_thePanePageMovesOntoTheOuterStack() {
        recreate(Window(twoPane = true, rail = true), Window(twoPane = false, rail = true))
        assertShownOnce(inPane = false)
    }

    @Test fun recreation_shrinking_whileTheRailDisappears_thePanePageIsNotLost() {
        recreate(Window(twoPane = true, rail = true), Window(twoPane = false, rail = false))
        assertShownOnce(inPane = false)
        onOuter { it.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("the list").assertIsDisplayed()
    }

    // A large phone, wide -> narrow -> backed out of the page -> wide again: the pane must not bring the page back.
    // (The pane's old nested controller state is still in the saved state; the counter that discards it is not found
    // again after the rail flips twice, unless it is kept with the host entry.)
    @Test fun recreation_aPageBackedOutOfDoesNotReappearInThePane_whenTheRailFlips() {
        val restoration = StateRestorationTester(rule)
        frozen = Window(twoPane = true, rail = true)
        show(setContent = restoration::setContent)
        rule.onNodeWithText("open b").performClick()
        assertShownOnce(inPane = true)
        frozen = Window(twoPane = false, rail = false)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertShownOnce(inPane = false)
        onOuter { it.popBackStack() }
        rule.waitForIdle()
        rule.onNodeWithText("the list").assertIsDisplayed()
        frozen = Window(twoPane = true, rail = true)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag("emptyPane").assertIsDisplayed()
        assertEquals("pane:b", 0, count("pane:b"))
    }

    @Test fun recreation_whileWide_keepsThePanePage() {
        recreate(Window(twoPane = true, rail = true), Window(twoPane = true, rail = true))
        assertShownOnce(inPane = true)
    }

    companion object {
        private val ONLINE = ConnectionState.Online("https://abs.example/".toHttpUrl(), ConnectionState.Via.PUBLIC)
    }
}
