package io.wolftown.lauschig.ui.setup

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.AuthFormData
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.absapi.auth.StatusResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountSignOut
import io.wolftown.lauschig.account.SignOutCheck
import io.wolftown.lauschig.account.SignOutResult
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

private class FakeLogin(var behaviour: (HttpUrl) -> LoginResponse) : LoginClient {
    val calls = mutableListOf<HttpUrl>()
    var statusResult: (HttpUrl) -> StatusResponse = { throw IOException("no status") }
    override suspend fun status(baseUrl: HttpUrl): StatusResponse = statusResult(baseUrl)
    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse {
        calls += baseUrl
        return behaviour(baseUrl)
    }
}

private class FakeOidc : OidcSignIn {
    override suspend fun clearPending() {}
    var start: OidcStart = OidcStart.Launch("https://idp.example/login")
    var finishResult: () -> OidcResult = { throw OidcException(OidcException.Reason.NO_PENDING) }
    val started = mutableListOf<HttpUrl>()
    val finished = mutableListOf<OidcCallback>()
    var lastFlow = OidcFlowContext()
    var matches = true
    override suspend fun start(baseUrl: HttpUrl, flow: OidcFlowContext): OidcStart { started += baseUrl; lastFlow = flow; return start }
    override suspend fun matchesPending(callback: OidcCallback) = matches
    override suspend fun finish(callback: OidcCallback): OidcResult { finished += callback; return finishResult() }
}

private class FakeSync : SyncTrigger {
    var count = 0
    override fun syncNow() { count++ }
}

private class FakeNet(var ssid: String? = null) : NetworkInfo {
    override val isConnected: Flow<Boolean> = flowOf(true)
    override suspend fun currentSsid() = ssid
}

/** Records which account was replaced by which (signed out keeping its data) and saves the next one like the real one. */
private class FakeSignOut(private val store: FakeAccountStore) : AccountSignOut {
    val switched = mutableListOf<Pair<String, String>>()
    override suspend fun check(): SignOutCheck? = null
    override suspend fun downloadBytes(): Long = 0
    override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult = error("not a sign-out")
    override suspend fun switchTo(next: Account, accessToken: String, refreshToken: String) {
        switched += store.acc.value!!.serverId to next.serverId
        store.save(next, accessToken, refreshToken)
    }

    /** Like the real one (SignOutTest checks it): another account is switched to, the same one (or none) saved. */
    override suspend fun signIn(next: Account, accessToken: String, refreshToken: String) {
        val previous = store.acc.value
        if (previous != null && previous.serverId != next.serverId) switchTo(next, accessToken, refreshToken)
        else store.save(next, accessToken, refreshToken)
    }
}

private fun ok(access: String? = "acc", refresh: String? = "ref", type: String = "user") =
    LoginResponse(AbsUser("u1", "fabian", type, access, refresh))

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = FakeAccountStore()
    private val login = FakeLogin { ok() }
    private val sync = FakeSync()
    private val net = FakeNet()
    private val oidc = FakeOidc()
    private val bus = OidcCallbackBus()
    private val signOut = FakeSignOut(store)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    private val viewModels = TestViewModels()
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(SetupViewModel(store, login, net, sync, oidc, bus, signOut))

    private fun SetupViewModel.fill(local: String = "", public: String = "", user: String = "fabian", pw: String = "secret") {
        onLocalChanged(local); onPublicChanged(public); onUsernameChanged(user); onPasswordChanged(pw)
    }

    @Test fun noAddress_disablesLogin() {
        val v = vm()
        v.fill()
        assertFalse(v.state.value.canLogin)
        v.onPublicChanged("https://abs.example.org")
        assertTrue(v.state.value.canLogin)
        v.onUsernameChanged("")
        assertFalse(v.state.value.canLogin)
        v.login()
        assertTrue(login.calls.isEmpty())
    }

    @Test fun httpAddress_requiresCleartextConfirmation() {
        val v = vm()
        v.fill(local = "http://192.168.1.5:13378")
        v.login()
        assertTrue("no request before confirmation", login.calls.isEmpty())
        assertEquals(listOf("http://192.168.1.5:13378"), v.state.value.cleartextPrompt)

        v.dismissCleartext()
        assertNull(v.state.value.cleartextPrompt)
        assertTrue(login.calls.isEmpty())

        v.login()
        v.confirmCleartext()
        assertEquals(1, login.calls.size)
        val account = store.acc.value
        assertNotNull(account)
        assertEquals(setOf("http://192.168.1.5:13378/".toHttpUrl().cleartextKey()), account!!.allowCleartext)
    }

    @Test fun oldServerWithoutTokens_showsServerTooOld() {
        login.behaviour = { ok(access = null, refresh = null) }
        val v = vm()
        v.fill(public = "https://abs.example.org")
        v.login()
        assertEquals(SetupError.SERVER_TOO_OLD, v.state.value.error)
        assertNull(store.acc.value)
        assertEquals(0, sync.count)
        assertFalse(v.state.value.loading)
    }

    @Test fun successfulLogin_savesAccount_andStartsLibrarySync() {
        login.behaviour = { ok(type = "admin") }
        val v = vm()
        v.fill(public = "https://abs.example.org/abs/")
        v.login()
        val a = store.acc.value!!
        assertEquals("u1", a.userId)
        assertEquals("fabian", a.username)
        assertTrue(a.isAdmin)
        assertEquals("https://abs.example.org/abs/".toHttpUrl(), a.addresses.public)
        assertTrue(a.allowCleartext.isEmpty())
        assertEquals("acc", store.access)
        assertEquals("ref", store.refresh)
        assertEquals(1, sync.count)
        assertTrue(v.state.value.done)
    }

    @Test fun localUnreachable_fallsBackToPublic() {
        login.behaviour = { if (it.host == "192.168.1.5") throw IOException("timeout") else ok() }
        val v = vm()
        v.fill(local = "192.168.1.5:13378", public = "https://abs.example.org")
        v.login()
        v.confirmCleartext() // a bare local address defaults to http -> needs confirmation
        assertEquals(listOf("192.168.1.5", "abs.example.org"), login.calls.map { it.host })
        assertTrue(v.state.value.done)
    }

    @Test fun bothUnreachable_showsUnreachable() {
        login.behaviour = { throw IOException("down") }
        val v = vm()
        v.fill(public = "https://abs.example.org")
        v.login()
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        assertNull(store.acc.value)
    }

    @Test fun wrongPassword_stopsWithoutTryingNextAddress() {
        login.behaviour = { throw HttpException(Response.error<Any>(401, "".toResponseBody(null))) }
        val v = vm()
        v.fill(local = "https://nas.lan", public = "https://abs.example.org")
        v.login()
        assertEquals(SetupError.WRONG_LOGIN, v.state.value.error)
        assertEquals(1, login.calls.size)
    }

    @Test fun ssidBinding_prefillsCurrentSsid_orShowsHint() {
        val v = vm()
        v.fill(local = "https://nas.lan", public = "https://abs.example.org")
        net.ssid = null
        v.enableSsidBinding()
        assertFalse(v.state.value.ssidBinding)
        assertTrue(v.state.value.ssidHint)

        net.ssid = "HomeNet"
        v.enableSsidBinding()
        assertTrue(v.state.value.ssidBinding)
        assertEquals("HomeNet", v.state.value.currentSsid)
        v.login()
        assertEquals(setOf("HomeNet"), store.acc.value!!.addresses.homeSsids)
    }

    private fun existingAccount(): Account {
        val addresses = ServerAddresses("http://192.168.1.5:13378".toHttpUrl(), "https://abs.example.org".toHttpUrl(), setOf("HomeNet"))
        return Account(Account.computeServerId(addresses, "u1"), "u1", "fabian", false, addresses, setOf("http://192.168.1.5:13378"))
    }

    @Test fun reauth_prefillsAddressesUsernameAndSsidFromCurrentAccount() {
        store.acc.value = existingAccount()
        store.reauth.value = true
        val st = vm().state.value
        assertEquals("http://192.168.1.5:13378", st.local)
        assertEquals("https://abs.example.org", st.public)
        assertEquals("fabian", st.username)
        assertEquals("", st.password)
        assertTrue(st.ssidBinding)
        assertEquals("HomeNet", st.currentSsid)
    }

    @Test fun reauth_success_keepsServerIdAndSsids_clearsFlag_withoutCleartextPromptAgain() {
        val before = existingAccount()
        store.acc.value = before
        store.reauth.value = true
        val v = vm()
        v.onPasswordChanged("secret")
        v.login()
        assertNull("confirmed earlier", v.state.value.cleartextPrompt)
        assertTrue(v.state.value.done)
        assertEquals(before.serverId, store.acc.value!!.serverId)
        assertEquals(setOf("HomeNet"), store.acc.value!!.addresses.homeSsids)
        assertEquals("acc", store.access)
        assertFalse(store.reauth.value)
        assertTrue("the same account is not signed out", signOut.switched.isEmpty())
    }

    @Test fun reauth_withAnotherAccount_signsTheOldOneOutKeepingItsData_beforeSaving() {
        val before = existingAccount()
        store.acc.value = before
        store.reauth.value = true
        login.behaviour = { LoginResponse(AbsUser("u2", "lukas", "user", "acc2", "ref2")) }
        val v = vm()
        v.onUsernameChanged("lukas")
        v.onPasswordChanged("secret")
        v.login()
        assertTrue(v.state.value.done)
        // Its work stops and its data stays reachable ("Downloads früherer Konten"), as with „Abmelden“ + „Behalten“,
        // and the new account takes its place in the same step.
        assertEquals(listOf(before.serverId to store.acc.value!!.serverId), signOut.switched)
        assertEquals("u2", store.acc.value!!.userId)
        assertNull("never cleared", store.clearedKeeping)
        assertEquals("acc2", store.access)
    }

    @Test fun reauth_withAnotherPublicAddress_isAnotherServerId_andSignsTheOldOneOut() {
        val before = existingAccount()
        store.acc.value = before
        store.reauth.value = true
        val v = vm()
        v.onPublicChanged("https://abs.example.net")
        v.onPasswordChanged("secret")
        v.login()
        assertTrue(v.state.value.done)
        assertTrue(before.serverId != store.acc.value!!.serverId)
        assertEquals(listOf(before.serverId to store.acc.value!!.serverId), signOut.switched)
    }

    @Test fun status_withOpenid_showsSsoButton() {
        login.statusResult = { StatusResponse("2.37.1", listOf("local", "openid"), AuthFormData(authOpenIDButtonText = "Mit Authentik")) }
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.ssoAvailable)
        assertEquals("Mit Authentik", v.state.value.ssoButtonText)

        login.statusResult = { StatusResponse("2.37.1", listOf("local")) }
        v.onPublicChanged("https://other.example.org")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.ssoAvailable)
        assertNull(v.state.value.ssoButtonText)
    }

    @Test fun status_failure_isIgnored() {
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.ssoAvailable)
        assertNull(v.state.value.error)
    }

    @Test fun oidcStart_invalidRedirect_showsAdminHint() {
        oidc.start = OidcStart.RedirectNotAllowed
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        v.loginWithSso()
        assertEquals(SetupError.SSO_REDIRECT_NOT_ALLOWED, v.state.value.error)
        assertFalse(v.state.value.loading)
        assertNull(v.state.value.ssoLaunchUrl)
    }

    @Test fun oidc_tabClosedWithoutCallback_resetsLoading() {
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        v.loginWithSso()
        assertTrue(v.state.value.loading)
        assertEquals("https://idp.example/login", v.state.value.ssoLaunchUrl)
        v.onSsoLaunched()
        assertNull(v.state.value.ssoLaunchUrl)
        v.onResumed()
        dispatcher.scheduler.advanceTimeBy(900)
        assertTrue("still within the 1 s grace", v.state.value.loading)
        dispatcher.scheduler.advanceTimeBy(200)
        assertFalse(v.state.value.loading)
        assertNull(v.state.value.error)
    }

    @Test fun oidc_callback_savesAccountLikePasswordLogin() {
        oidc.finishResult = { OidcResult("https://abs.example.org/".toHttpUrl(), ok()) }
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        v.loginWithSso()
        v.onSsoLaunched()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(listOf(OidcCallback("c", "s", null)), oidc.finished)
        assertTrue(v.state.value.done)
        assertEquals("acc", store.access)
        assertEquals(1, sync.count)
    }

    @Test fun oidc_callbackWithoutRefreshToken_showsServerTooOld() {
        oidc.finishResult = { OidcResult("https://abs.example.org/".toHttpUrl(), ok(refresh = null)) }
        val v = vm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(SetupError.SERVER_TOO_OLD, v.state.value.error)
    }

    @Test fun oidc_callbackAfterRestart_usesTheAddressItWasStartedWith() {
        oidc.finishResult = { OidcResult("http://192.168.1.5:13378/".toHttpUrl(), ok()) }
        val v = vm() // fresh process: nothing typed
        bus.post(OidcCallback("c", "s", null))
        assertTrue(v.state.value.done)
        assertEquals("http://192.168.1.5:13378/", store.acc.value?.addresses?.local.toString())
        assertNull(store.acc.value?.addresses?.public)
    }

    @Test fun oidc_staleCallback_isIgnored() {
        bus.post(OidcCallback("c", "s", null), atMs = 0)
        val v = vm()
        assertTrue(oidc.finished.isEmpty())
        assertFalse(v.state.value.loading)
    }

    @Test fun oidc_afterRestart_savesSameAccountAsPasswordLogin() {
        // Password login with both addresses and SSID binding.
        val v1 = vm()
        v1.fill(local = "https://nas.lan", public = "https://abs.example.org")
        net.ssid = "HomeNet"
        v1.enableSsidBinding()
        v1.login()
        val expected = store.acc.value!!

        // SSO: start, then "restart" (new VM, empty form), then the callback.
        store.acc.value = null
        val first = vm()
        first.fill(local = "https://nas.lan", public = "https://abs.example.org")
        first.enableSsidBinding()
        first.loginWithSso()
        oidc.finishResult = { OidcResult("https://abs.example.org/".toHttpUrl(), ok(), oidc.lastFlow) }
        val freshBus = OidcCallbackBus() // the first VM is gone with its process
        val second = viewModels.own(SetupViewModel(store, login, net, sync, oidc, freshBus, signOut))
        freshBus.post(OidcCallback("c", "s", null))
        assertTrue(second.state.value.done)
        assertFalse(first.state.value.done)
        val a = store.acc.value!!
        assertEquals(expected.addresses, a.addresses)
        assertEquals(expected.serverId, a.serverId)
        assertEquals(setOf("HomeNet"), a.addresses.homeSsids)
    }

    @Test fun oidc_foreignCallback_duringPasswordLogin_changesNothing() {
        oidc.matches = false
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        v.loginWithSso() // loading = true, as in a running login
        val before = v.state.value
        bus.post(OidcCallback("c", "forged", null))
        assertEquals(before, v.state.value)
        assertTrue(oidc.finished.isEmpty())
    }

    @Test fun oidc_foreignCallback_keepsExistingError() {
        oidc.matches = false
        login.behaviour = { throw IOException("down") }
        val v = vm()
        v.fill(public = "https://abs.example.org")
        v.login()
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        val before = v.state.value
        bus.post(OidcCallback("c", "s", null))
        assertEquals(before, v.state.value)
    }
}
