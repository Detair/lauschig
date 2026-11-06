package io.wolftown.lauschig.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
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
    private fun Page(name: String) {
        val model = viewModel<PageModel>()
        if (model !in everModel) everModel += model
        models[name] = model
        val visits = rememberSaveable { mutableStateOf(0) }
        Text("$owner:$name:${visits.value}")
    }

    @Composable
    private fun Pages() {
        AccountPages(owner, scope) { pages ->
            nav = pages.controller
            AccountNavHost(pages, startDestination = if (owner == null) Setup() else Start) {
                composable<Setup> { Page("setup") }
                composable<Start> { Page("start") }
                composable<Library> { Page("library") }
                composable<Settings> { Page("settings") }
                composable<ItemDetail> { Page("item") }
                composable<Player> { Page("player") }
            }
        }
    }

    private fun show() = rule.setContent { Pages() }

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

    @Test fun theSystemBack_isNotHeldByTheNavigationBefore() {
        lateinit var back: androidx.activity.OnBackPressedDispatcher
        rule.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            Pages()
        }
        go { nav.navigate(Settings) }
        rule.runOnUiThread { assertTrue(back.hasEnabledCallbacks()) }
        owner = "b"
        rule.waitForIdle()
        rule.runOnUiThread { assertFalse(back.hasEnabledCallbacks()) }
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
        restore.setContent { Pages() }
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
        restore.setContent { Pages() }
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }
        val before = models.getValue("settings")

        scope = AccountPagesScope() // what is kept across a rotation is gone with the process
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf("start", "library", "settings"), stack())
        assertNotSame(before, models.getValue("settings"))
    }

    // A's pages were saved, then the account changed, before the app came back: Home during a switch, then the system
    // recreated the activity or ended the process. What is saved is A's; StateRestorationTester cannot change the owner
    // between saving and restoring, so this does the same steps by hand.

    private var alive by mutableStateOf(true)
    private var restored: Map<String, List<Any?>>? = null
    private lateinit var registry: SaveableStateRegistry

    private fun showRecreatable() = rule.setContent {
        if (alive) {
            val current = remember { SaveableStateRegistry(restored) { true } }
            registry = current
            CompositionLocalProvider(LocalSaveableStateRegistry provides current) { Pages() }
        }
    }

    private fun recreateWith(next: String?, sameProcess: Boolean) {
        rule.runOnIdle { restored = registry.performSave() }
        alive = false
        rule.waitForIdle()
        if (!sameProcess) scope = AccountPagesScope()
        owner = next
        alive = true
        rule.waitForIdle()
    }

    private fun aDeepPageThenRecreatedOnTheNextAccount(sameProcess: Boolean) {
        showRecreatable()
        go { nav.navigateToTab(TopLevelTab.Library) }
        go { nav.navigate(Settings) }
        recreateWith("b", sameProcess)
        assertEquals(listOf("start"), stack())
    }

    @Test fun aSwitchThatEndedWhileTheProcessWasGone_doesNotBringTheOtherPagesBack() = aDeepPageThenRecreatedOnTheNextAccount(sameProcess = false)

    /** Review M2. */
    @Test fun afterThat_backOnTheAccountBefore_itsOldPagesDoNotComeBack() {
        aDeepPageThenRecreatedOnTheNextAccount(sameProcess = false)
        owner = "a"
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
    }

    @Test fun afterARecreationInTheSameProcess_itsOldPagesDoNotComeBackEither() {
        aDeepPageThenRecreatedOnTheNextAccount(sameProcess = true)
        owner = "a"
        rule.waitForIdle()
        assertEquals(listOf("start"), stack())
    }

    @Test fun andWhatIsRecreatedOnTheSameAccount_comesBack() {
        showRecreatable()
        go { nav.navigate(Settings) }
        recreateWith("a", sameProcess = true)
        assertEquals(listOf("start", "settings"), stack())
    }

    @Test fun theActivityIsLeftWithoutOldNavigationsRegistered() {
        val host = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides host) { Pages() } }
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
