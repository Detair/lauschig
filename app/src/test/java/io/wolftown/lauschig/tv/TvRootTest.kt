package io.wolftown.lauschig.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import io.wolftown.lauschig.AppPages
import io.wolftown.lauschig.tv.nav.TvHome
import io.wolftown.lauschig.tv.nav.TvProfiles
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.wolftown.lauschig.ui.nav.AccountNavHost
import io.wolftown.lauschig.ui.nav.AccountPagesScope
import io.wolftown.lauschig.ui.nav.ProfilePicker
import io.wolftown.lauschig.ui.nav.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which page the TV starts on (TV spec E8), and that a profile switch leaves nothing of the pages before (review focus 5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvRootTest {
    @get:Rule val rule = createComposeRule()

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private val scope = AccountPagesScope()
    private val probes = mutableMapOf<String?, Probe>()

    private var pages by mutableStateOf(AppPages("aaaa", Start))
    private var count by mutableStateOf<Int?>(2)

    @Composable
    private fun Content() = TvAccountPages(pages, count, scope) { account, start ->
        probes[pages.owner] = viewModel<Probe>(viewModelStoreOwner = account.models)
        Text("${pages.owner} starts on $start")
    }

    private fun shows(owner: String?, start: Any) = rule.onNodeWithText("$owner starts on $start").assertExists()

    @Test fun severalProfiles_askWhoIsListening_evenWithOneActive() {
        rule.setContent { Content() }
        shows("aaaa", TvProfiles)
    }

    @Test fun oneProfile_startsOnTheStartPage() {
        count = 1
        rule.setContent { Content() }
        shows("aaaa", TvHome)
    }

    @Test fun nobodyActive_isAskedAnyway() {
        count = 1
        pages = AppPages(null, ProfilePicker)
        rule.setContent { Content() }
        shows(null, TvProfiles)
    }

    @Test fun untilTheProfilesAreRead_nothingShows() {
        count = null
        rule.setContent { Content() }
        rule.onNodeWithText("starts on", substring = true).assertDoesNotExist()
        count = 2
        rule.waitForIdle()
        shows("aaaa", TvProfiles)
    }

    /** Asked once per start: the profile chosen there starts on its start page, and the pages before end with their view models. */
    @Test fun afterASwitch_theNextProfileStartsAtHome_andNothingOfTheFirstStays() {
        rule.setContent { Content() }
        shows("aaaa", TvProfiles)
        val first = probes.getValue("aaaa")
        pages = AppPages("bbbb", Start)
        rule.waitForIdle()
        shows("bbbb", TvHome)
        rule.onNodeWithText("aaaa", substring = true).assertDoesNotExist()
        assertTrue(first.cleared)
        assertEquals(false, probes.getValue("bbbb").cleared)
    }

    @Test fun aRecreation_doesNotAskAgain_butKeepsThePagesItHas() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Content() }
        shows("aaaa", TvProfiles)
        tester.emulateSavedInstanceStateRestore()
        // The first pages keep the start they were built on: their back stack comes back on top of it.
        shows("aaaa", TvProfiles)
        pages = AppPages("bbbb", Start)
        rule.waitForIdle()
        shows("bbbb", TvHome)
    }

    /**
     * As it happens: asked, chosen, now on the start page; then the activity is recreated. The start page stays, the question
     * does not come back, and the profile chosen next starts on its start page too.
     */
    @Test fun aRecreation_afterTheQuestion_staysOnThePageWentTo() {
        lateinit var nav: NavHostController
        val tester = StateRestorationTester(rule)
        tester.setContent {
            TvAccountPages(pages, count, scope) { account, start ->
                AccountNavHost(account, start) {
                    composable<TvProfiles> { Text("${pages.owner} who is listening") }
                    composable<TvHome> { Text("${pages.owner} home") }
                }
                nav = account.controller
            }
        }
        rule.onNodeWithText("aaaa who is listening").assertExists()
        rule.runOnIdle { nav.navigate(TvHome) { popUpTo<TvProfiles> { inclusive = true } } }
        rule.onNodeWithText("aaaa home").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("aaaa home").assertExists()
        rule.onNodeWithText("aaaa who is listening").assertDoesNotExist()
        pages = AppPages("bbbb", Start)
        rule.waitForIdle()
        rule.onNodeWithText("bbbb home").assertExists()
    }
}
