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
        assertEquals(Setup(), viewModels.own(MainViewModel(store)).startDestination.value)
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

    @Test fun noAccount_setup() = assertEquals(Setup(), viewModels.own(MainViewModel(FakeAccountStore())).startDestination.value)

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
        assertEquals(Setup(), viewModels.own(MainViewModel(FakeAccountStore(), tabLayoutSource = kotlinx.coroutines.flow.flowOf(layout))).startDestination.value)
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

    @Test fun signedIn_followsTheAccount_aSignOutWhileRunningTurnsItFalse() {
        val store = signedIn()
        val vm = viewModels.own(MainViewModel(store))
        assertEquals(true, vm.signedIn.value)
        kotlinx.coroutines.runBlocking { store.clear(keepDownloads = true) }
        assertEquals(false, vm.signedIn.value)
        assertEquals(Setup(), vm.startDestination.value) // the sign-in, with new pages (AccountPages)
        kotlinx.coroutines.runBlocking { store.save(store.acc.value ?: Account("s2", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet()), "a", "r") }
        assertEquals(true, vm.signedIn.value)
    }

    private fun account(id: String) = Account(id, "u-$id", "n-$id", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet())

    @Test fun pages_belongToTheActiveAccount_aSwitchMeansNewPagesOnTheStartTab() {
        val layout = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT.withStart(io.wolftown.lauschig.ui.TopLevelTab.Library)
        val store = FakeAccountStore().apply {
            acc.value = account("a")
            aside.value = listOf(FakeAccountStore.Aside(account("b"), "acc", "ref", false))
        }
        val vm = viewModels.own(MainViewModel(store, tabLayoutSource = kotlinx.coroutines.flow.flowOf(layout)))
        assertEquals(AppPages("a", io.wolftown.lauschig.ui.nav.Library), vm.pages.value)
        kotlinx.coroutines.runBlocking { store.activate("b") }
        assertEquals(AppPages("b", io.wolftown.lauschig.ui.nav.Library), vm.pages.value)
    }

    @Test fun pages_stayTheSame_whileTheAccountStays() {
        val store = signedIn()
        val vm = viewModels.own(MainViewModel(store))
        val pages = vm.pages.value
        // A token refresh or a changed address of the same account: the pages go on.
        kotlinx.coroutines.runBlocking { store.save(store.acc.value!!.copy(username = "neu"), "a2", "r2") }
        assertEquals(true, pages === vm.pages.value)
    }

    @Test fun pages_signedOut_theSignIn_thenTheChosenStartTab() {
        val store = signedIn()
        val vm = viewModels.own(MainViewModel(store))
        kotlinx.coroutines.runBlocking { store.clear(keepDownloads = false) }
        assertEquals(AppPages(null, Setup()), vm.pages.value)
        kotlinx.coroutines.runBlocking { store.save(account("b"), "a", "r") }
        assertEquals(AppPages("b", Start), vm.pages.value)
    }

    @Test fun pages_anUnreadableStore_isTheSignIn_andStaysUp() {
        val store = object : io.wolftown.lauschig.account.AccountStore by FakeAccountStore() {
            override val account: Flow<Account?> = flow { throw IOException("corrupt") }
        }
        assertEquals(AppPages(null, Setup()), viewModels.own(MainViewModel(store)).pages.value)
    }

    private fun withSaved(active: Account? = null) = FakeAccountStore().apply {
        acc.value = active
        aside.value = listOf(FakeAccountStore.Aside(account("b"), "acc", "ref", false))
    }

    @Test fun pages_noActiveAccount_savedOnes_thePicker_notTheSignIn() {
        assertEquals(AppPages(null, io.wolftown.lauschig.ui.nav.ProfilePicker), viewModels.own(MainViewModel(withSaved())).pages.value)
    }

    @Test fun pages_signingOutWithAnotherProfileLeft_thePicker_noAutomaticSwitch() {
        val store = withSaved(account("a"))
        val vm = viewModels.own(MainViewModel(store))
        kotlinx.coroutines.runBlocking { store.clear(keepDownloads = true) }
        assertEquals(AppPages(null, io.wolftown.lauschig.ui.nav.ProfilePicker), vm.pages.value)
        assertEquals(null, store.acc.value)
    }

    @Test fun pages_anUnreadableSavedList_isTheSignIn() {
        val store = object : io.wolftown.lauschig.account.AccountStore by FakeAccountStore() {
            override val saved: Flow<List<Account>> = flow { throw IOException("corrupt") }
        }
        assertEquals(AppPages(null, Setup()), viewModels.own(MainViewModel(store)).pages.value)
    }

    private val welcome = io.wolftown.lauschig.ui.nav.Welcome

    @Test fun theVeryFirstStart_theWelcome() {
        assertEquals(AppPages(null, welcome), viewModels.own(MainViewModel(FakeAccountStore(), welcomeDue = { true })).pages.value)
    }

    @Test fun theWelcome_notWithAnAccount() {
        assertEquals(AppPages("s1", Start), viewModels.own(MainViewModel(signedIn(), welcomeDue = { true })).pages.value)
    }

    @Test fun theWelcome_notWithSavedProfiles() {
        assertEquals(io.wolftown.lauschig.ui.nav.ProfilePicker, viewModels.own(MainViewModel(withSaved(), welcomeDue = { true })).pages.value!!.start)
    }

    @Test fun theWelcome_notWhenNotDue_theSignIn() {
        assertEquals(Setup(), viewModels.own(MainViewModel(FakeAccountStore(), welcomeDue = { false })).pages.value!!.start)
    }

    @Test fun theWelcome_onlyAtTheStart_notAfterASignOut() {
        val store = signedIn()
        val vm = viewModels.own(MainViewModel(store, welcomeDue = { true }))
        kotlinx.coroutines.runBlocking { store.clear(keepDownloads = false) }
        assertEquals(Setup(), vm.pages.value!!.start)
    }

    @Test fun theWelcome_unreadable_neverInTheWayOfTheStart() {
        assertEquals(Setup(), viewModels.own(MainViewModel(FakeAccountStore(), welcomeDue = { throw IOException("corrupt") })).pages.value!!.start)
    }

    @Test fun signedIn_unreadableStore_isFalse() {
        val store = object : io.wolftown.lauschig.account.AccountStore by FakeAccountStore() {
            override val account: Flow<Account?> = flow { throw IOException("corrupt") }
        }
        assertEquals(false, viewModels.own(MainViewModel(store)).signedIn.value)
    }
}
