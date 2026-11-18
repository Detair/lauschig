package io.wolftown.lauschig.tv.settings

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.FakeProfileLocks
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.account.ProfileSwitch
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.ProfileGate
import io.wolftown.lauschig.ui.profiles.ProfileOperations
import io.wolftown.lauschig.ui.profiles.ProfilesViewModel
import io.wolftown.lauschig.ui.profiles.ProtectionStep
import io.wolftown.lauschig.ui.profiles.RemovalFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the settings route does with the profiles' protection flow, on the real view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TvSettingsActionsTest {
    private val anna = Account("aaaaaaaaaaaaaaaa", "u1", "Anna", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
    private val store = FakeAccountStore().apply { acc.value = anna }
    private val locks = FakeProfileLocks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val viewModels = TestViewModels()

    private val noSwitch = object : ProfileSwitch {
        override suspend fun switchProfile(serverId: String) = false
        override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) = Unit
        override suspend fun removeProfile(serverId: String) = false
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); scope.cancel(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(
        ProfilesViewModel(store, ProfileOperations(noSwitch, scope), ProfileGate(locks) { false }) { RemovalFacts(0, 0, false) },
    )

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test fun leavingForTheSignIn_endsAPendingPinQuestion() {
        runBlocking { locks.setPin(anna.serverId, "2468") }
        val profiles = vm()
        await { profiles.profiles.filterNotNull().first { rows -> rows.any { it.protection == ProfileLockKind.PIN } } }
        profiles.changeProtection()
        await { profiles.gatePrompt.first { it is GatePrompt.Pin } }
        val signIns = mutableListOf<String>()
        leaveForSignIn(profiles, anna.serverId) { signIns += it }
        assertNull(profiles.gatePrompt.value)
        assertNull(profiles.protection.value)
        assertEquals(listOf(anna.serverId), signIns)
    }

    @Test fun leavingForTheSignIn_endsAProtectionStep() {
        val profiles = vm()
        await { profiles.profiles.filterNotNull().first { it.isNotEmpty() } }
        profiles.changeProtection()
        await { profiles.protection.first { it is ProtectionStep.Choose } }
        profiles.chooseProtection(ProfileLockKind.PIN)
        assertTrue(profiles.protection.value is ProtectionStep.NewPin)
        leaveForSignIn(profiles, anna.serverId) {}
        assertNull(profiles.protection.value)
        assertNull(profiles.gatePrompt.value)
    }
}
