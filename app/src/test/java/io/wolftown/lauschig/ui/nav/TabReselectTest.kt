package io.wolftown.lauschig.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.wolftown.lauschig.ui.TopLevelTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tapping the tab that is already active returns to its root; the other tabs keep their pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TabReselectTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun show(start: Any = Start) = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = start) {
            composable<Start> { Text("start") }
            composable<Library> { Text("library") }
            composable<Discover> { Text("discover") }
            composable<Settings> { Text("settings") }
            composable<ItemDetail> { Text("detail ${it.toRoute<ItemDetail>().itemId}") }
        }
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { e ->
        val d = e.destination
        when {
            d.hasRoute<Start>() -> "start"
            d.hasRoute<Library>() -> "library"
            d.hasRoute<Discover>() -> "discover"
            d.hasRoute<Settings>() -> "settings"
            d.hasRoute<ItemDetail>() -> "detail:${e.toRoute<ItemDetail>().itemId}"
            else -> null
        }
    }

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    @Test fun startTab_withSettingsOpen_reTapReturnsToStart() {
        show()
        ui { nav.navigate(Settings) }
        ui { nav.navigateToTab(TopLevelTab.Start) }
        rule.onNodeWithText("start").assertIsDisplayed()
        assertEquals(listOf("start"), stack())
    }

    @Test fun libraryTab_withTwoPagesOpen_reTapReturnsToTheLibrary() {
        show()
        ui { nav.navigateToTab(TopLevelTab.Library) }
        ui { nav.navigate(ItemDetail("a")); nav.navigate(ItemDetail("b")) }
        ui { nav.navigateToTab(TopLevelTab.Library) }
        rule.onNodeWithText("library").assertIsDisplayed()
        assertEquals(listOf("start", "library"), stack())
    }

    @Test fun reTapAtTheRoot_changesNothing() {
        show()
        ui { nav.navigateToTab(TopLevelTab.Library) }
        val before = nav.currentBackStackEntry?.id
        ui { nav.navigateToTab(TopLevelTab.Library) }
        assertEquals(listOf("start", "library"), stack())
        // The same entry: the page keeps its state (scroll position, view model).
        assertEquals(before, nav.currentBackStackEntry?.id)
        ui { nav.navigateToTab(TopLevelTab.Start) }
        ui { nav.navigateToTab(TopLevelTab.Start) }
        assertEquals(listOf("start"), stack())
    }

    @Test fun anotherTab_stillRestoresItsPages() {
        show()
        ui { nav.navigateToTab(TopLevelTab.Library) }
        ui { nav.navigate(ItemDetail("a")) }
        ui { nav.navigateToTab(TopLevelTab.Start) }
        ui { nav.navigate(Settings) }
        // Library comes back with its page, as before.
        ui { nav.navigateToTab(TopLevelTab.Library) }
        rule.onNodeWithText("detail a").assertIsDisplayed()
        assertEquals(listOf("start", "library", "detail:a"), stack())
        // And Start, re-entered from Library, still has its Settings.
        ui { nav.navigateToTab(TopLevelTab.Start) }
        assertEquals(listOf("start", "settings"), stack())
        // Re-tapping it now goes to its root.
        ui { nav.navigateToTab(TopLevelTab.Start) }
        assertEquals(listOf("start"), stack())
    }

    @Test fun theHomeTabNeedNotBeStart() {
        show(start = Library)
        ui { nav.navigateToTab(TopLevelTab.Start) }
        ui { nav.navigate(Settings) }
        ui { nav.navigateToTab(TopLevelTab.Start) }
        assertEquals(listOf("library", "start"), stack())
        ui { nav.navigateToTab(TopLevelTab.Library) }
        ui { nav.navigate(ItemDetail("x")) }
        ui { nav.navigateToTab(TopLevelTab.Library) }
        assertEquals(listOf("library"), stack())
    }
}
