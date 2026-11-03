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
class SignOutNavigationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun show(start: Any) = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = start) {
            composable<Setup> { Text("setup") }
            composable<Start> { Text("start") }
            composable<Library> { Text("library") }
            composable<Settings> { Text("settings") }
        }
    }

    private fun stack() = nav.currentBackStack.value.mapNotNull { e ->
        when {
            e.destination.hasRoute<Setup>() -> "setup"
            e.destination.hasRoute<Start>() -> "start"
            e.destination.hasRoute<Library>() -> "library"
            e.destination.hasRoute<Settings>() -> "settings"
            else -> null
        }
    }

    @Test fun signedOutDeepInTheApp_onlyTheSignInIsLeft_thenTheNextSignInLandsOnTheStartTab() {
        show(Start)
        rule.runOnUiThread { nav.navigateToTab(TopLevelTab.Library); nav.navigate(Settings) }
        assertEquals(listOf("start", "library", "settings"), stack())

        rule.runOnUiThread { nav.showSignIn() }
        rule.onNodeWithText("setup").assertIsDisplayed()
        assertEquals(listOf("setup"), stack())
        rule.runOnUiThread { assertFalse(nav.popBackStack()) } // "back" never returns to the old account's pages

        // A new sign-in (the same or another account) starts on the chosen start screen, as the first one did.
        rule.runOnUiThread { nav.finishSetup(Library) }
        rule.onNodeWithText("library").assertIsDisplayed()
        assertEquals(listOf("library"), stack())
        assertTrue(nav.graph.findStartDestination().hasRoute<Library>())
    }

    @Test fun alreadyOnTheSignIn_withoutAccount_nothingChanges() {
        show(Setup)
        val before = nav.currentBackStack.value.last().id
        rule.runOnUiThread { nav.showSignIn() }
        assertEquals(listOf("setup"), stack())
        assertEquals(before, nav.currentBackStack.value.last().id)
    }

    @Test fun signedOutWhileTheReauthSetupIsOpen_staysOnASingleSetup() {
        show(Start)
        rule.runOnUiThread { nav.navigate(Setup) } // "sign in again" opens Setup on top of the app
        rule.runOnUiThread { nav.showSignIn() }
        assertEquals(listOf("setup"), stack())
        rule.runOnUiThread { nav.finishSetup(Start) }
        assertEquals(listOf("start"), stack())
    }
}
