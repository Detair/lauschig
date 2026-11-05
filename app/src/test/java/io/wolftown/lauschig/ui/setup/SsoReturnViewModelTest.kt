package io.wolftown.lauschig.ui.setup

import io.wolftown.lauschig.TestViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Review focus 4: back from the browser with no form to receive it; the app opens one. */
@OptIn(ExperimentalCoroutinesApi::class)
class SsoReturnViewModelTest {
    private val bus = OidcCallbackBus()
    private var matches = true
    private val oidc = object : OidcSignIn {
        override suspend fun start(baseUrl: HttpUrl, flow: OidcFlowContext): OidcStart = OidcStart.Unreachable
        override suspend fun matchesPending(callback: OidcCallback) = matches
        override suspend fun finish(callback: OidcCallback): OidcResult = error("not here")
        override suspend fun clearPending() {}
    }
    private val viewModels = TestViewModels()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(SsoReturnViewModel(bus, oidc))

    @Test fun nothingBack_nothingWaits() = assertFalse(vm().waiting.value)

    @Test fun theAnswerToTheAttempt_waits_untilAFormTookIt() {
        val vm = vm()
        bus.post(OidcCallback("c", "s", null))
        assertTrue(vm.waiting.value)
        bus.take()
        assertFalse(vm.waiting.value)
    }

    @Test fun aForeignLink_opensNothing() {
        matches = false
        val vm = vm()
        bus.post(OidcCallback("c", "other", null))
        assertFalse(vm.waiting.value)
    }
}
