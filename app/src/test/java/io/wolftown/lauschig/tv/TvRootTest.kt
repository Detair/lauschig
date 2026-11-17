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
    private fun content() = TvAccountPages(pages, count, scope) { account, start ->
        probes[pages.owner] = viewModel<Probe>(viewModelStoreOwner = account.models)
        Text("${pages.owner} starts on $start")
    }

    private fun shows(owner: String?, start: Any) = rule.onNodeWithText("$owner starts on $start").assertExists()

    @Test fun severalProfiles_askWhoIsListening_evenWithOneActive() {
        rule.setContent { content() }
        shows("aaaa", TvProfiles)
    }

    @Test fun oneProfile_startsOnTheStartPage() {
        count = 1
        rule.setContent { content() }
        shows("aaaa", TvHome)
    }

    @Test fun nobodyActive_isAskedAnyway() {
        count = 1
        pages = AppPages(null, ProfilePicker)
        rule.setContent { content() }
        shows(null, TvProfiles)
    }

    @Test fun untilTheProfilesAreRead_nothingShows() {
        count = null
        rule.setContent { content() }
        rule.onNodeWithText("starts on", substring = true).assertDoesNotExist()
        count = 2
        rule.waitForIdle()
        shows("aaaa", TvProfiles)
    }

    /** Asked once per start: the profile chosen there starts on its start page, and the pages before end with their view models. */
    @Test fun afterASwitch_theNextProfileStartsAtHome_andNothingOfTheFirstStays() {
        rule.setContent { content() }
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
        tester.setContent { content() }
        shows("aaaa", TvProfiles)
        tester.emulateSavedInstanceStateRestore()
        // The first pages keep the start they were built on: their back stack comes back on top of it.
        shows("aaaa", TvProfiles)
        pages = AppPages("bbbb", Start)
        rule.waitForIdle()
        shows("bbbb", TvHome)
    }
}
