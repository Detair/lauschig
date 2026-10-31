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
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun unreadableAccountStore_fallsBackToSetup_withoutCrash() {
        val store = object : io.wolftown.lauschig.account.AccountStore by FakeAccountStore() {
            override val account: Flow<Account?> = flow { throw IOException("corrupt") }
        }
        assertEquals(Setup, MainViewModel(store).startDestination.value)
    }

    @Test fun noAccount_setup() = assertEquals(Setup, MainViewModel(FakeAccountStore()).startDestination.value)
}
