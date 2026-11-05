package io.wolftown.lauschig.ui.welcome

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.nav.Welcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class WelcomeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(page: Int) = rule.setContent {
        LauschigTheme { WelcomeScreen(page, onNext = { calls += "next" }, onSkip = { calls += "skip" }) }
    }

    @Test fun theFirstPage_nextAndSkip() {
        show(0)
        rule.onNodeWithText("Welcome to Lauschig").assertExists()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(listOf("next", "skip"), calls)
    }

    @Test fun aServerIsSaidToBeNeeded_plainly() {
        show(1)
        rule.onNodeWithText("You need your own server").assertExists()
        rule.onNode(hasText("Audiobookshelf", substring = true)).assertExists()
        rule.onNode(hasText("you can't sign in", substring = true)).assertExists()
    }

    @Test fun theLastPage_offersTheSignIn_nothingToSkip() {
        show(WELCOME_PAGE_COUNT - 1)
        rule.onNodeWithText("Skip").assertDoesNotExist()
        rule.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("next"), calls)
    }

    @Test fun talkBack_theHeading_thePlace_andANewPageIsRead() {
        show(1)
        rule.onNodeWithText("You need your own server").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNode(hasContentDescription("Page 2 of 3")).assertExists()
        rule.onNodeWithTag("welcomePage").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    /** Through the real route: what a process death leaves, the entry's saved state. */
    @Test fun aRecreationKeepsThePage_withoutItsViewModel() {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = Welcome) {
                composable<Welcome> {
                    val vm = viewModel { WelcomeViewModel(createSavedStateHandle()) {} }
                    val page by vm.page.collectAsStateWithLifecycle()
                    Text("page $page")
                    if (page == 0) vm.next()
                }
            }
        }
        rule.onNodeWithText("page 1").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("page 1").assertExists()
    }
}
