package io.wolftown.lauschig.ui.welcome

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.accessibilityInfo
import io.wolftown.lauschig.ui.nav.Welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class WelcomeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(page: Int) = rule.setContent {
        LauschigTheme { WelcomeScreen(page, onNext = { calls += "next" }, onSkip = { calls += "skip" }) }
    }

    private fun page() = rule.onNodeWithTag("welcomePage")
    private fun SemanticsNodeInteraction.spoken() = fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    @Test fun theFirstPage_nextAndSkip() {
        show(0)
        assertTrue(page().spoken().startsWith("Welcome to Lauschig"))
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(listOf("next", "skip"), calls)
    }

    @Test fun aServerIsSaidToBeNeeded_plainly() {
        show(1)
        val spoken = page().spoken()
        assertTrue(spoken, spoken.startsWith("You need your own server"))
        assertTrue(spoken, "Audiobookshelf" in spoken)
        assertTrue(spoken, "you can't sign in" in spoken)
    }

    @Test fun theLastPage_offersTheSignIn_nothingToSkip() {
        show(WELCOME_PAGE_COUNT - 1)
        rule.onNodeWithText("Skip").assertDoesNotExist()
        rule.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("next"), calls)
    }

    @Test fun talkBack_theHeading_andThePlace() {
        show(1)
        page().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNode(hasContentDescription("Page 2 of 3")).assertExists()
    }

    /** A live region speaks when a node it knows changes; a new node for each page would be silent. */
    @Test fun talkBack_aNewPageIsTheSameLiveNode_withOtherWords() {
        var shown by mutableStateOf(0)
        rule.setContent { LauschigTheme { WelcomeScreen(shown, onNext = {}, onSkip = {}) } }
        val before = page().fetchSemanticsNode().id
        val firstWords = page().spoken()
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, page().accessibilityInfo()!!.liveRegion)

        shown = 1
        rule.waitForIdle()
        assertEquals(before, page().fetchSemanticsNode().id)
        assertNotEquals(firstWords, page().spoken())
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, page().accessibilityInfo()!!.liveRegion)
    }

    /** Review M5: German, twice the text size, a narrow phone. No button may break inside its word. */
    @Test
    @Config(sdk = [35], application = android.app.Application::class, qualifiers = "de-w360dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text widths
    fun twiceTheTextSize_onANarrowPhone_theButtonsKeepTheirWords() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                LauschigTheme { WelcomeScreen(0, onNext = {}, onSkip = {}) }
            }
        }
        for (label in listOf("Weiter", "Überspringen")) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            assertEquals(label, 1, layouts.single().lineCount)
            rule.onNodeWithText(label).assertIsDisplayed()
        }
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
