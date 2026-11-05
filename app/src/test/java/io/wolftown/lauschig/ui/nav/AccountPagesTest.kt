package io.wolftown.lauschig.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.wolftown.lauschig.ui.TopLevelTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review focus 1: what is left on screen and alive after a switch; rotation and process death in between. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AccountPagesTest {
    @get:Rule val rule = createComposeRule()

    /** Stands for a page's view model: `GuardedCallFactory` relies on none of these outliving its account. */
    class PageModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private var owner by mutableStateOf<String?>("a")
    private lateinit var nav: NavHostController
    private var scope = AccountPagesScope()
    private val models = mutableMapOf<String, PageModel>()
    private val everModel = mutableListOf<PageModel>()

    @Composable
    private fun page(name: String) {
        val model = viewModel<PageModel>()
        if (model !in everModel) everModel += model
        models[name] = model
        val visits = rememberSaveable { mutableStateOf(0) }
        Text("$owner:$name:${visits.value}")
    }

    @Composable
    private fun pages() {
        AccountPages(owner, scope) { navController, pageModels ->
            nav = navController
            CompositionLocalProvider(LocalViewModelStoreOwner provides pageModels) {
                NavHost(navController, startDestination = if (owner == null) Setup() else Start) {
                    composable<Setup> { page("setup") }
                    composable<Start> { page("start") }
                    composable<Library> { page("library") }
                    composable<Settings> { page("settings") }
                    composable<ItemDetail> { page("item") }
                    composable<Player> { page("player") }
                }
            }
        }
    }

    private fun show() = rule.setContent { pages() }

    private fun stack() = nav.currentBackStack.value.mapNotNull { e ->
        when {
            e.destination.hasRoute<Setup>() -> "setup"
            e.destination.hasRoute<Start>() -> "start"
            e.destination.hasRoute<Library>() -> "library"
            e.destination.hasRoute<Settings>() -> "settings"
            e.destination.hasRoute<ItemDetail>() -> "item"
            e.destination.hasRoute<Player>() -> "player"
            else -> null
        }
    }

    private fun go(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.waitForIdle()
    }

    @Test fun afterASwitch_onlyTheStartOfTheNextAccountIsThere_withThePlayerClosed() {
        show()
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(ItemDetail("a-book")) }
        go { nav.navigate(Player) }
        assertEquals(listOf("start", "library", "item", "player"), stack())

        owner = "b"
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
        rule.onNodeWithText("b:start:0").assertExists()
    }

    @Test fun aSwitch_endsTheViewModelsOfEveryPage_alsoOfTheTabsThatWereSavedAway() {
        show()
        go { nav.navigateToTab(TopLevelTab.Library) } // start, library
        go { nav.navigate(ItemDetail("a-book")) }
        go { nav.navigateToTab(TopLevelTab.Start) } // library and its title are saved away
        assertEquals(listOf("start"), stack())
        val ofA = everModel.toList()
        assertEquals(3, ofA.size)
        assertFalse(ofA.any { it.cleared })

        owner = "b"
        rule.waitForIdle()
        assertTrue("every view model of A ended", ofA.all { it.cleared })
        assertFalse(models.getValue("start").cleared) // B's own
    }

    @Test fun aTabOfTheAccountBefore_doesNotComeBack() {
        show()
        go { nav.navigateToTab(TopLevelTab.Library) }
        val itsModel = models.getValue("library")
        go { nav.navigate(ItemDetail("a-book")) }
        go { nav.navigateToTab(TopLevelTab.Start) }

        owner = "b"
        rule.waitForIdle()
        go { nav.navigateToTab(TopLevelTab.Library) }
        assertEquals(listOf("start", "library"), stack()) // no title of A on top
        assertNotSame(itsModel, models.getValue("library"))
    }

    @Test fun backAfterASwitch_neverReturnsToTheAccountBefore() {
        show()
        go { nav.navigate(Settings) }
        owner = "b"
        rule.waitForIdle()
        go { assertFalse(nav.popBackStack()) }
    }

    @Test fun thereAndBackAgain_startsAfresh() {
        show()
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }
        owner = "b"
        rule.waitForIdle()
        owner = "a"
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
    }

    @Test fun signedOutDeepInTheApp_onlyTheSignInIsLeft_thenTheNextSignInLandsOnTheStartTab() {
        show()
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }
        val ofA = everModel.toList()

        owner = null
        rule.waitForIdle()
        assertEquals(listOf("setup"), stack())
        assertTrue(ofA.all { it.cleared })
        go { assertFalse(nav.popBackStack()) }

        owner = "b"
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
        assertTrue(nav.graph.findStartDestination().hasRoute<Start>())
    }

    @Test fun turningTheDevice_keepsThePagesAndTheirViewModels() {
        val restore = StateRestorationTester(rule)
        restore.setContent { pages() }
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }
        val settings = models.getValue("settings")

        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("start", "library", "settings"), stack())
        assertSame(settings, models.getValue("settings"))
        assertFalse(everModel.any { it.cleared })
    }

    @Test fun aProcessDeath_bringsThePagesBack_withNewViewModels() {
        val restore = StateRestorationTester(rule)
        restore.setContent { pages() }
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }

        scope = AccountPagesScope() // what is kept across a rotation is gone with the process
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("start", "library", "settings"), stack())
    }

    @Test fun aSwitchThatEndedWhileTheProcessWasGone_doesNotBringTheOtherPagesBack() {
        val restore = StateRestorationTester(rule)
        restore.setContent { pages() }
        go { nav.navigate(Settings) }

        scope = AccountPagesScope()
        owner = "b"
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
    }

    @Test fun theActivityIsLeftWithoutOldNavigationsRegistered() {
        val host = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides host) { pages() } }
        rule.waitForIdle()
        val atFirst = host.registry.observerCount
        repeat(4) {
            owner = if (it % 2 == 0) "b" else "a"
            rule.waitForIdle()
        }
        assertEquals(atFirst, host.registry.observerCount)
    }

    @Test fun whenTheActivityEndsForGood_theModelsEndToo() {
        show()
        val model = models.getValue("start")
        rule.runOnUiThread { scope.ended() }
        assertTrue(model.cleared)
    }
}
