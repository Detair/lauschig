package io.wolftown.lauschig.ui.nav

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.TopLevelTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FinishSetupTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun show(start: Any) = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = start) {
            composable<Setup> { Text("setup") }
            composable<Start> { Text("start") }
            composable<Library> { Text("library") }
        }
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { e ->
        when {
            e.destination.hasRoute<Setup>() -> "setup"
            e.destination.hasRoute<Start>() -> "start"
            e.destination.hasRoute<Library>() -> "library"
            else -> null
        }
    }

    @Test fun firstSignIn_opensTheChosenStartScreen_whichBecomesHome() {
        show(Setup)
        rule.runOnUiThread { nav.finishSetup(Library) }
        rule.onNodeWithText("library").assertIsDisplayed()
        assertEquals(listOf("library"), stack())
        assertTrue(nav.graph.findStartDestination().hasRoute<Library>())
        // Another tab, then back: the start screen, then out.
        rule.runOnUiThread { nav.navigateToTab(TopLevelTab.Start) }
        assertEquals(listOf("library", "start"), stack())
        rule.runOnUiThread { nav.popBackStack() }
        assertEquals(listOf("library"), stack())
        rule.runOnUiThread { assertFalse(nav.popBackStack()) }
    }

    @Test fun reauthOnTopOfTheApp_justGoesBack() {
        show(Start)
        rule.runOnUiThread { nav.navigate(Setup) }
        rule.runOnUiThread { nav.finishSetup(Library) }
        rule.onNodeWithText("start").assertIsDisplayed()
        assertEquals(listOf("start"), stack())
    }
}
