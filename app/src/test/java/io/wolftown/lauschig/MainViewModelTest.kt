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
}
