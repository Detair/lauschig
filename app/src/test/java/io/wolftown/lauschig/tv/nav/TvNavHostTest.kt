package io.wolftown.lauschig.tv.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Text
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the rail leads: every section stands right on the start page, so Back from it leads there and from there out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvNavHostTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun show() = rule.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = TvHome) {
            composable<TvHome> { Text("home") }
            composable<TvLibrary> { Text("library") }
            composable<TvSearch> { Text("search") }
            composable<TvSettings> { Text("settings") }
        }
    }

    private fun stack(): List<String> = nav.currentBackStack.value.mapNotNull { entry ->
        val d = entry.destination
        when {
            d.hasRoute<TvHome>() -> "home"
            d.hasRoute<TvLibrary>() -> "library"
            d.hasRoute<TvSearch>() -> "search"
            d.hasRoute<TvSettings>() -> "settings"
            else -> null
        }
    }

    private fun open(section: TvSection) {
        rule.runOnIdle { nav.openSection(section) }
        rule.waitForIdle()
    }

    @Test fun aSection_standsOnTheStartPage() {
        show()
        open(TvSection.Library)
        assertEquals(listOf("home", "library"), stack())
        open(TvSection.Search)
        assertEquals("not on top of the library", listOf("home", "search"), stack())
        open(TvSection.Search)
        assertEquals("only once", listOf("home", "search"), stack())
    }

    @Test fun home_goesBackToTheStartPage_andBackThenLeaves() {
        show()
        open(TvSection.Settings)
        open(TvSection.Home)
        assertEquals(listOf("home"), stack())
        // Nothing below it: the host lets Back go to the activity, which ends.
        rule.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }
}
