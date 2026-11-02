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
        assertEquals(Start, viewModels.own(MainViewModel(signedIn) { runs++ }).startDestination.value)
        assertEquals(1, runs)
        viewModels.own(MainViewModel(FakeAccountStore()) { runs++ })
        assertEquals(1, runs)
    }

    @Test fun continueOnStart_failure_doesNotBreakTheStart() {
        val signedIn = FakeAccountStore().apply { acc.value = Account("s1", "u", "n", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet()) }
        assertEquals(Start, viewModels.own(MainViewModel(signedIn) { error("db gone") }).startDestination.value)
    }

    @Test fun noAccount_setup() = assertEquals(Setup, viewModels.own(MainViewModel(FakeAccountStore())).startDestination.value)
}
