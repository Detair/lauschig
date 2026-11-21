package io.wolftown.lauschig.tv.setup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.absapi.auth.StatusResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountSignOut
import io.wolftown.lauschig.account.SignOutCheck
import io.wolftown.lauschig.account.SignOutResult
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tvsetup.HandoverReply
import io.wolftown.lauschig.tvsetup.HomeNetwork
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.tvsetup.TvSetupCode
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.setup.LoginClient
import io.wolftown.lauschig.ui.setup.OidcCallback
import io.wolftown.lauschig.ui.setup.OidcCallbackBus
import io.wolftown.lauschig.ui.setup.OidcException
import io.wolftown.lauschig.ui.setup.OidcFlowContext
import io.wolftown.lauschig.ui.setup.OidcResult
import io.wolftown.lauschig.ui.setup.OidcSignIn
import io.wolftown.lauschig.ui.setup.OidcStart
import io.wolftown.lauschig.ui.setup.SetupViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import io.wolftown.lauschig.tvsetup.ListenResult
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

/** No server answers; [refreshes] counts what would have reached one. */
private class NoServer : LoginClient {
    var refreshes = 0
    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse = throw IOException("no server")
    override suspend fun status(baseUrl: HttpUrl): StatusResponse = throw IOException("no server")
    override suspend fun refresh(baseUrl: HttpUrl, refreshToken: String): LoginResponse {
        refreshes++
        throw IOException("no server")
    }
}

private class NoOidc : OidcSignIn {
    override suspend fun start(baseUrl: HttpUrl, flow: OidcFlowContext): OidcStart = OidcStart.Unreachable
    override suspend fun matchesPending(callback: OidcCallback) = false
    override suspend fun finish(callback: OidcCallback): OidcResult = throw OidcException(OidcException.Reason.NO_PENDING)
    override suspend fun clearPending() {}
}

private class NoSignOut : AccountSignOut {
    override suspend fun check(): SignOutCheck? = null
    override suspend fun downloadBytes(): Long = 0
    override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult = error("not here")
    override suspend fun switchTo(next: Account, accessToken: String, refreshToken: String) = error("not here")
    override suspend fun signIn(next: Account, accessToken: String, refreshToken: String) = error("not here")
}

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
/** The route's own decision between code page and form, through the real [TvSetupRoute] and its view models. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvSetupRouteTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val store = ViewModelStore()
    private val server = NoServer()
    private var listens = 0

    /** When set, the fake listener receives this packet after the code is out, and records what the phone is told. */
    private var packet: CompletableDeferred<PhoneHandover>? = null
    private val replies = CompletableDeferred<HandoverReply>()

    @After fun tearDown() = store.clear()

    private inline fun <reified T : androidx.lifecycle.ViewModel> own(vm: T): T =
        ViewModelProvider.create(store, viewModelFactory { initializer { vm } })["${T::class.simpleName}", T::class]

    private fun setup(mode: SetupMode, unlockTarget: String? = null) = own(
        SetupViewModel(
            FakeAccountStore(), server, object : NetworkInfo {
                override val isConnected: Flow<Boolean> = flowOf(true)
                override suspend fun currentSsid(): String? = null
            },
            object : SyncTrigger { override fun syncNow() {} }, NoOidc(), OidcCallbackBus(), NoSignOut(),
            mode = mode, unlockTarget = unlockTarget,
        ),
    )

    private fun phone() = own(
        TvPhoneSetupViewModel(
            object : HomeNetwork {
                override fun ownAddress(): Inet4Address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 50)) as Inet4Address
                override fun open(): Socket? = null
            },
            listen = { _, onReady, signIn ->
                listens++
                onReady(TvSetupCode("192.168.1.50", 41234, ByteArray(32)))
                val arriving = packet ?: awaitCancellation()
                val reply = signIn(arriving.await())
                replies.complete(reply)
                ListenResult.Finished(reply)
            },
        ),
    )

    private fun route(viewModel: SetupViewModel, phone: TvPhoneSetupViewModel) {
        // Not advanced: what shows is the very first frame.
        rule.mainClock.autoAdvance = false
        rule.setContent { TvTheme { TvSetupRoute(onDone = {}, viewModel = viewModel, phone = phone) } }
    }

    @Test fun forgottenPin_onlyTheForm_andNoPortFromTheFirstFrameOn() {
        val viewModel = setup(SetupMode.UNLOCK, unlockTarget = "aaaaaaaaaaaaaaaa")
        assertTrue("the mode is in the first state", viewModel.state.value.addressesFixed)
        route(viewModel, phone())
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PASSWORD).assertExists()
        assertEquals(0, listens)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PASSWORD).assertExists()
        assertEquals("no port opened", 0, listens)
    }

    @Test fun signIn_theCodeComesFirst() {
        route(setup(SetupMode.SIGN_IN), phone())
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertExists()
        rule.onNodeWithTag(TAG_PASSWORD).assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_QR).assertExists()
        assertEquals(1, listens)
    }

    @Test fun addProfile_theCodeComesFirst() {
        route(setup(SetupMode.ADD_PROFILE), phone())
        rule.onNodeWithTag(TAG_PHONE_TYPE).assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, listens)
    }

    // --- Spec E17 through the route: nothing reaches a server before the person says yes.

    private fun aPacketArrives() {
        packet = CompletableDeferred()
        route(setup(SetupMode.SIGN_IN), phone())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        packet!!.complete(PhoneHandover(local = "http://192.168.1.20:13378/", refresh = "r"))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(TAG_PHONE_CANCEL).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ok() {
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
    }

    @Test fun aPacket_isAskedAbout_andCancelSendsNothing() {
        aPacketArrives()
        rule.onNodeWithTag(TAG_PHONE_CANCEL).assertIsFocused()
        assertEquals(0, server.refreshes)
        ok()
        rule.waitUntil(5_000) { replies.isCompleted }
        assertEquals(HandoverReply.DECLINED, replies.getCompleted())
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_AGAIN).assertIsFocused()
        assertEquals(0, server.refreshes)
    }

    @Test fun aPacket_backIsANo() {
        aPacketArrives()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { replies.isCompleted }
        assertEquals(HandoverReply.DECLINED, replies.getCompleted())
        assertEquals(0, server.refreshes)
    }

    @Test fun aPacket_setUp_onlyThenTheServerIsAsked() {
        aPacketArrives()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PHONE_CONFIRM).assertIsFocused()
        ok()
        rule.waitUntil(5_000) { replies.isCompleted }
        assertEquals("no server answers here", HandoverReply.SERVER_UNREACHABLE, replies.getCompleted())
        assertEquals(1, server.refreshes)
    }
}
