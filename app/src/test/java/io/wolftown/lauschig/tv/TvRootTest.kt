package io.wolftown.lauschig.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.wolftown.lauschig.links.VoiceRequest
import io.wolftown.lauschig.ui.profiles.ProfileOutcome
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import io.wolftown.lauschig.AppPages
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.tv.nav.TvPlayer
import io.wolftown.lauschig.tv.nav.TvHome
import io.wolftown.lauschig.tv.nav.TvProfiles
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.wolftown.lauschig.ui.nav.AccountNavHost
import io.wolftown.lauschig.ui.nav.AccountPagesScope
import io.wolftown.lauschig.ui.nav.ProfilePicker
import io.wolftown.lauschig.ui.nav.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which page the TV starts on (TV spec E8), and that a profile switch leaves nothing of the pages before (review focus 5). */
@OptIn(ExperimentalTestApi::class)
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

    private var request by mutableStateOf<String?>(null)
    private var loaded = true

    /** The real host's order: the pages, then the request on top of them. */
    private fun showWithRequest(): () -> NavHostController {
        lateinit var nav: NavHostController
        rule.setContent {
            TvAccountPages(pages, count, scope) { account, start ->
                AccountNavHost(account, start) {
                    composable<TvProfiles> { Text("who is listening") }
                    composable<TvHome> { Text("home") }
                    composable<TvPlayer> { Text("player") }
                }
                TvOpenRequest(account.controller, request, onHandled = { request = null }) { loaded }
                nav = account.controller
            }
        }
        return { nav }
    }

    private fun back(nav: NavHostController) {
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
    }

    /** From the now-playing card into a fresh start: the player, and Back from it leads to the start page (review focus 3). */
    @Test fun openPlayer_withoutHistory_backLeadsToTheStartPage() {
        count = 1
        request = MainActivity.OPEN_PLAYER
        val nav = showWithRequest()
        rule.onNodeWithText("player").assertExists()
        rule.runOnIdle { assertNull(request) }
        back(nav())
        rule.onNodeWithText("home").assertExists()
    }

    /** With several profiles the question comes first; the player waits for its answer and then opens on top of home. */
    @Test fun openPlayer_waitsForWhoIsListening() {
        request = MainActivity.OPEN_PLAYER
        val nav = showWithRequest()
        rule.onNodeWithText("who is listening").assertExists()
        rule.runOnIdle { assertEquals(MainActivity.OPEN_PLAYER, request) }
        rule.runOnIdle { nav().navigate(TvHome) { popUpTo<TvProfiles> { inclusive = true } } }
        rule.waitForIdle()
        rule.onNodeWithText("player").assertExists()
        back(nav())
        rule.onNodeWithText("home").assertExists()
    }

    @Test fun openPlayer_withNothingLoaded_staysWhereItIs() {
        count = 1
        loaded = false
        request = MainActivity.OPEN_PLAYER
        showWithRequest()
        rule.onNodeWithText("home").assertExists()
        rule.onNodeWithText("player").assertDoesNotExist()
        rule.runOnIdle { assertNull(request) }
    }

    @Test fun aRequestTheTvHasNoPageFor_isLetGo() {
        count = 1
        request = MainActivity.OPEN_CLEANUP
        showWithRequest()
        rule.onNodeWithText("home").assertExists()
        rule.runOnIdle { assertNull(request) }
    }

    private var voice by mutableStateOf<VoiceRequest?>(null)
    private val played = mutableListOf<String?>()

    private fun showWithVoice(): () -> NavHostController {
        lateinit var nav: NavHostController
        rule.setContent {
            TvAccountPages(pages, count, scope) { account, start ->
                AccountNavHost(account, start) {
                    composable<TvProfiles> { Text("who is listening") }
                    composable<TvHome> { Text("home") }
                }
                TvVoiceRequest(account.controller, voice, onHandled = { voice = null }) { played += it.query }
                nav = account.controller
            }
        }
        return { nav }
    }

    /** The Assistant's request on a TV with one profile: played at once, and only once. */
    @Test fun aVoiceRequest_withOneProfile_playsAtOnce() {
        count = 1
        voice = VoiceRequest("Der kleine Hörtest", null)
        showWithVoice()
        rule.onNodeWithText("home").assertExists()
        rule.runOnIdle {
            assertEquals(listOf<String?>("Der kleine Hörtest"), played)
            assertNull(voice)
        }
    }

    /** With several profiles "who is listening?" (and a PIN) comes first: a voice must not play a protected profile's titles. */
    @Test fun aVoiceRequest_waitsForWhoIsListening() {
        voice = VoiceRequest("Der kleine Hörtest", null)
        val nav = showWithVoice()
        rule.onNodeWithText("who is listening").assertExists()
        rule.runOnIdle { assertEquals(emptyList<String?>(), played) }
        rule.runOnIdle { nav().navigate(TvHome) { popUpTo<TvProfiles> { inclusive = true } } }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf<String?>("Der kleine Hörtest"), played) }
    }

    @Test fun anUnlock_isSaid_andOkTakesItOff() {
        val shown = mutableListOf<ProfileOutcome>()
        val unlocked = ProfileOutcome(ProfileOutcome.Kind.UNLOCKED, "Ben", 1)
        rule.setContent { TvTheme { TvUnlockedNotice(unlocked) { shown += it } } }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        rule.onNodeWithText(context.getString(io.wolftown.lauschig.R.string.profiles_unlocked, "Ben")).assertExists()
        rule.onNodeWithTag(io.wolftown.lauschig.tv.profiles.TAG_OUTCOME_OK).assertIsFocused()
        // A TV button answers OK, not a touch.
        rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(listOf(unlocked), shown) }
    }

    @Test fun aSwitch_isNotSaidThere() {
        rule.setContent { TvTheme { TvUnlockedNotice(ProfileOutcome(ProfileOutcome.Kind.SWITCHED, "Ben", 1)) {} } }
        rule.onNodeWithTag(io.wolftown.lauschig.tv.profiles.TAG_OUTCOME_DIALOG).assertDoesNotExist()
    }
}
