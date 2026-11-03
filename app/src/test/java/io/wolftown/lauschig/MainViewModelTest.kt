package io.wolftown.lauschig

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.Start
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    private val viewModels = TestViewModels()
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    @Test fun unreadableAccountStore_fallsBackToSetup_withoutCrash() {
        val store = object : io.wolftown.lauschig.account.AccountStore by FakeAccountStore() {
            override val account: Flow<Account?> = flow { throw IOException("corrupt") }
        }
        assertEquals(Setup, viewModels.own(MainViewModel(store)).startDestination.value)
    }

    @Test fun continueOnStart_runsOnceWithAnAccount_neverWithout() {
        var runs = 0
        val signedIn = FakeAccountStore().apply { acc.value = Account("s1", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet()) }
        val vm = viewModels.own(MainViewModel(signedIn) { runs++ })
        assertEquals(Start, vm.startDestination.value)
        assertEquals(0, runs) // only a launcher start continues
        vm.continueOnLauncherStart()
        vm.continueOnLauncherStart()
        assertEquals(1, runs)
        viewModels.own(MainViewModel(FakeAccountStore()) { runs++ }).continueOnLauncherStart()
        assertEquals(1, runs)
    }

    @Test fun continueOnStart_failure_doesNotBreakTheStart() {
        val signedIn = FakeAccountStore().apply { acc.value = Account("s1", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet()) }
        val vm = viewModels.own(MainViewModel(signedIn) { error("db gone") })
        vm.continueOnLauncherStart()
        assertEquals(Start, vm.startDestination.value)
    }

    @Test fun noAccount_setup() = assertEquals(Setup, viewModels.own(MainViewModel(FakeAccountStore())).startDestination.value)

    @Test fun appearance_nullUntilRead_thenTheStoredLook() {
        val source = kotlinx.coroutines.flow.MutableSharedFlow<io.wolftown.lauschig.settings.Appearance>()
        val vm = viewModels.own(MainViewModel(FakeAccountStore(), source))
        assertEquals(null, vm.appearance.value) // the splash waits for this
        val dark = io.wolftown.lauschig.settings.Appearance(io.wolftown.lauschig.settings.ThemeMode.DARK, pureBlack = true)
        kotlinx.coroutines.runBlocking { source.emit(dark) }
        assertEquals(dark, vm.appearance.value)
    }

    @Test fun appearance_unreadableStore_givesDefaults() {
        val vm = viewModels.own(MainViewModel(FakeAccountStore(), flow { throw IOException("corrupt") }))
        assertEquals(io.wolftown.lauschig.settings.Appearance(), vm.appearance.value)
    }

    private fun signedIn() = FakeAccountStore().apply {
        acc.value = Account("s1", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet())
    }

    @Test fun startDestination_isTheChosenStartTab() {
        val layout = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT.withStart(io.wolftown.lauschig.ui.TopLevelTab.Library)
        val vm = viewModels.own(MainViewModel(signedIn(), tabLayoutSource = kotlinx.coroutines.flow.flowOf(layout)))
        assertEquals(io.wolftown.lauschig.ui.nav.Library, vm.startDestination.value)
        assertEquals(layout, vm.tabLayout.value)
    }

    @Test fun startDestination_withoutAccount_isSetup_whateverTheLayout() {
        val layout = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT.withStart(io.wolftown.lauschig.ui.TopLevelTab.Search)
        assertEquals(Setup, viewModels.own(MainViewModel(FakeAccountStore(), tabLayoutSource = kotlinx.coroutines.flow.flowOf(layout))).startDestination.value)
    }

    @Test fun startDestination_unreadableLayout_isStart() {
        val vm = viewModels.own(MainViewModel(signedIn(), tabLayoutSource = flow { throw IOException("corrupt") }))
        assertEquals(Start, vm.startDestination.value)
    }

    @Test fun startDestination_staysFixed_whenTheLayoutChangesLater() {
        val source = kotlinx.coroutines.flow.MutableStateFlow(io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT)
        val vm = viewModels.own(MainViewModel(signedIn(), tabLayoutSource = source))
        assertEquals(Start, vm.startDestination.value)
        source.value = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT.withStart(io.wolftown.lauschig.ui.TopLevelTab.Discover)
        assertEquals(Start, vm.startDestination.value) // used on the next cold start
        assertEquals(io.wolftown.lauschig.ui.TopLevelTab.Discover, vm.tabLayout.value.start) // the bar follows at once
    }

    @Test fun continueOnStart_runsAlsoWithAnotherStartScreen() {
        var runs = 0
        val layout = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT.withStart(io.wolftown.lauschig.ui.TopLevelTab.Library)
        val vm = viewModels.own(MainViewModel(signedIn(), tabLayoutSource = kotlinx.coroutines.flow.flowOf(layout)) { runs++ })
        assertEquals(io.wolftown.lauschig.ui.nav.Library, vm.startDestination.value)
        vm.continueOnLauncherStart()
        assertEquals(1, runs)
    }
}
