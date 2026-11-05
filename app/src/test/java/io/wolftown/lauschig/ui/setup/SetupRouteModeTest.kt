package io.wolftown.lauschig.ui.setup

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.nav.Start
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review focus 4: what the form's view model reads ([setupMode]) through the real route, a rotation and a process death. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SetupRouteModeTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nav: NavHostController

    private fun content(tester: StateRestorationTester) = tester.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = Start) {
            composable<Start> { Text("start") }
            composable<Setup> { entry -> Text("form ${setupMode(entry.savedStateHandle)}") }
        }
    }

    private fun openAdd() {
        rule.runOnUiThread { nav.navigate(Setup(SetupMode.ADD_PROFILE)) }
        rule.waitForIdle()
    }

    @Test fun thePlainRoute_isASignIn() {
        rule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Setup()) { composable<Setup> { entry -> Text("form ${setupMode(entry.savedStateHandle)}") } }
        }
        rule.onNodeWithText("form SIGN_IN").assertExists()
    }

    @Test fun theModeIsInTheRoute() {
        content(StateRestorationTester(rule))
        openAdd()
        rule.onNodeWithText("form ADD_PROFILE").assertExists()
    }

    @Test fun theModeSurvivesARecreation() {
        val tester = StateRestorationTester(rule)
        content(tester)
        openAdd()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("form ADD_PROFILE").assertExists()
        // "back" still returns to the account's own page.
        rule.runOnUiThread { assertEquals(true, nav.popBackStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("start").assertExists()
    }
}
