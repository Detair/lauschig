package io.wolftown.lauschig.ui.setup

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Review focus 4: back from the browser with no form to receive it; the app opens one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SsoReturnViewModelTest {
    private val bus = OidcCallbackBus()
    private var matches = true
    private var adds = false
    private var unlock: String? = null
    private val oidc = object : OidcSignIn {
        override suspend fun start(baseUrl: HttpUrl, flow: OidcFlowContext): OidcStart = OidcStart.Unreachable
        override suspend fun matchesPending(callback: OidcCallback) = matches
        override suspend fun finish(callback: OidcCallback): OidcResult = error("not here")
        override suspend fun clearPending() {}
        override suspend fun pendingAddsProfile() = adds
        override suspend fun pendingUnlock() = unlock
    }
    private val viewModels = TestViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(SsoReturnViewModel(bus, oidc))

    @Test fun nothingBack_nothingWaits() = assertNull(vm().waiting.value)

    @Test fun theAnswerToTheAttempt_waits_untilAFormTookIt() {
        val vm = vm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(Setup(), vm.waiting.value)
        bus.take()
        assertNull(vm.waiting.value)
    }

    /** Review M1: the form that opens is what the attempt started as, also if the attempt then fails. */
    @Test fun theAnswerToAnAddAttempt_opensAnAddForm() {
        adds = true
        val vm = vm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(Setup(SetupMode.ADD_PROFILE), vm.waiting.value)
    }

    /** Task 3.6: the form that opens is that account's, and its mode is in the route from there on. */
    @Test fun theAnswerToAnUnlockAttempt_opensThatAccountsForm() {
        unlock = "aaaaaaaaaaaaaaaa"
        val vm = vm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(Setup(SetupMode.UNLOCK, "aaaaaaaaaaaaaaaa"), vm.waiting.value)
    }

    @Test fun aForeignLink_opensNothing() {
        matches = false
        val vm = vm()
        bus.post(OidcCallback("c", "other", null))
        assertNull(vm.waiting.value)
    }
}
