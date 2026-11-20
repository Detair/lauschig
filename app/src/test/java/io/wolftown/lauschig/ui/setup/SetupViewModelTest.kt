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
import io.wolftown.lauschig.serverqr.QrCode
import io.wolftown.lauschig.serverqr.QrFrameDecoder
import io.wolftown.lauschig.serverqr.decode
import io.wolftown.lauschig.serverqr.qrFrame
import io.wolftown.lauschig.serverqr.ServerQr
import io.wolftown.lauschig.serverqr.ServerQrError
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.tvsetup.HandoverReply
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.ui.nav.SetupMode
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    /** A server that has not answered yet. */
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun status(baseUrl: HttpUrl): StatusResponse = statusResult(baseUrl)
    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse {
        calls += baseUrl
        gate?.await()
        return behaviour(baseUrl)
    }
    /** The handed-over session's exchanges: address and the refresh token sent. */
    val refreshes = mutableListOf<Pair<HttpUrl, String>>()
    var refreshBehaviour: (HttpUrl) -> LoginResponse = { LoginResponse(AbsUser("u1", "fabian", "user", "tv-acc", "tv-ref")) }
    /** A server that has not answered the exchange yet. */
    var refreshGate: CompletableDeferred<Unit>? = null
    override suspend fun refresh(baseUrl: HttpUrl, refreshToken: String): LoginResponse {
        refreshes += baseUrl to refreshToken
        refreshGate?.await()
        return refreshBehaviour(baseUrl)
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
    var pendingAdd = false
    override suspend fun pendingAddsProfile() = pendingAdd
    var pendingUnlockOf: String? = null
    override suspend fun pendingUnlock() = pendingUnlockOf
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

    // --- Phase 7, 2.3: another profile. Review focus 4 and 5.

    private val fabian = Account("aaaaaaaaaaaaaaaa", "u0", "root", true, ServerAddresses("http://10.0.2.2:13378".toHttpUrl(), "https://abs.example.org".toHttpUrl(), setOf("HomeNet")), setOf("http://10.0.2.2:13378"))
    private val added = mutableListOf<Account>()
    private var addFails: Exception? = null

    private fun addVm(mode: SetupMode = SetupMode.ADD_PROFILE) = viewModels.own(
        SetupViewModel(store, login, net, sync, oidc, bus, signOut, addProfile = { a, acc, ref ->
            addFails?.let { throw it }
            added += a
            store.add(a, acc, ref)
        }, mode = mode),
    )

    private fun signedInAsFabian() { store.acc.value = fabian; store.access = "a0"; store.refresh = "r0" }

    @Test fun addProfile_theAddressesAreThere_nameAndPasswordEmpty() {
        signedInAsFabian()
        val s = addVm().state.value
        assertTrue(s.addProfile)
        assertEquals("http://10.0.2.2:13378", s.local)
        assertEquals("https://abs.example.org", s.public)
        assertEquals("", s.username)
        assertEquals("", s.password)
        assertFalse(s.canLogin)
    }

    @Test fun addProfile_theFirstForm_isNoAddForm() {
        signedInAsFabian()
        val s = vm().state.value
        assertFalse(s.addProfile)
        assertEquals("root", s.username)
    }

    @Test fun addProfile_theKnownPlainAddress_isNotAskedAgain() {
        signedInAsFabian()
        val v = addVm()
        v.fill(local = "http://10.0.2.2:13378", user = "kind")
        v.login()
        assertNull(v.state.value.cleartextPrompt)
        assertEquals(1, login.calls.size)
    }

    @Test fun addProfile_fromThePicker_theAddressesOfASavedProfile() {
        store.aside.value = listOf(FakeAccountStore.Aside(fabian, "a0", "r0", false))
        val s = addVm().state.value
        assertEquals("https://abs.example.org", s.public)
        assertEquals("", s.username)
    }

    @Test fun addProfile_signedIn_setsTheOldAccountAside_itStaysSignedIn() {
        signedInAsFabian()
        login.behaviour = { LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")) }
        val v = addVm()
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        assertEquals("kind", store.acc.value!!.username)
        assertEquals(listOf("kind"), added.map { it.username })
        assertEquals(listOf(fabian), store.aside.value.map { it.account })
        assertEquals("a0", store.aside.value.single().access)
        assertTrue("no sign-out of the old account", signOut.switched.isEmpty())
        assertTrue(v.state.value.done)
        assertEquals("", v.state.value.password)
    }

    @Test fun addProfile_back_fromTheForm_changesNothing() {
        signedInAsFabian()
        val v = addVm()
        v.fill(public = "https://abs.example.org", user = "kind")
        viewModels.clear()
        assertEquals(fabian, store.acc.value)
        assertTrue(store.aside.value.isEmpty())
        assertTrue(added.isEmpty())
        assertTrue(login.calls.isEmpty())
    }

    /** Back while the server is still answering: the answer, whatever it is, signs nobody in. */
    @Test fun addProfile_back_duringARunningLogin_changesNothing() {
        signedInAsFabian()
        login.gate = CompletableDeferred()
        login.behaviour = { LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")) }
        val v = addVm()
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        assertTrue(v.state.value.loading)
        assertEquals(1, login.calls.size)

        viewModels.clear()
        login.gate!!.complete(Unit)
        assertEquals(fabian, store.acc.value)
        assertTrue(store.aside.value.isEmpty())
        assertTrue(added.isEmpty())
        assertEquals(0, sync.count)
    }

    @Test fun addProfile_wrongPassword_saysSo_theOldAccountGoesOn() {
        signedInAsFabian()
        login.behaviour = { throw HttpException(Response.error<Any>(401, "".toResponseBody(null))) }
        val v = addVm()
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        assertEquals(SetupError.WRONG_LOGIN, v.state.value.error)
        assertFalse(v.state.value.loading)
        assertFalse(v.state.value.done)
        assertEquals(fabian, store.acc.value)
        assertTrue(added.isEmpty())
    }

    @Test fun addProfile_serverUnreachable_saysSo_theOldAccountGoesOn() {
        signedInAsFabian()
        login.behaviour = { throw IOException("no route") }
        val v = addVm()
        v.fill(public = "https://elsewhere.example.org", user = "kind")
        v.login()
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        assertFalse(v.state.value.done)
        assertEquals(fabian, store.acc.value)
        assertTrue(added.isEmpty())
    }

    @Test fun addProfile_cannotBeStored_saysSo_theOldAccountGoesOn() {
        signedInAsFabian()
        login.behaviour = { LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")) }
        addFails = IOException("disk full")
        val v = addVm()
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        assertEquals(SetupError.SAVE_FAILED, v.state.value.error)
        assertFalse(v.state.value.done)
        assertEquals(fabian, store.acc.value)
    }

    @Test fun addProfile_theSsoAttemptCarriesTheMode() {
        signedInAsFabian()
        val v = addVm()
        v.loginWithSso()
        assertTrue(oidc.lastFlow.addProfile)
    }

    @Test fun signIn_theSsoAttemptIsNoAdd() {
        signedInAsFabian()
        val v = vm()
        v.loginWithSso()
        assertFalse(oidc.lastFlow.addProfile)
    }

    /** The process died while the browser was up; the form comes back from its route. */
    @Test fun addProfile_ssoBackAfterAProcessDeath_addsAndKeepsTheOldAccount() {
        signedInAsFabian()
        oidc.finishResult = { OidcResult("https://abs.example.org".toHttpUrl(), LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")), OidcFlowContext(null, "https://abs.example.org/", addProfile = true)) }
        val v = addVm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals("kind", store.acc.value!!.username)
        assertEquals(listOf(fabian), store.aside.value.map { it.account })
        assertTrue(signOut.switched.isEmpty())
        assertTrue(v.state.value.done)
    }

    /** Nothing came back but the attempt (the app had to open a plain form): what it started as still counts. */
    @Test fun addProfile_ssoBackOnAPlainForm_theAttemptDecides() {
        signedInAsFabian()
        oidc.finishResult = { OidcResult("https://abs.example.org".toHttpUrl(), LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")), OidcFlowContext(null, "https://abs.example.org/", addProfile = true)) }
        addVm(SetupMode.SIGN_IN)
        bus.post(OidcCallback("c", "s", null))
        assertEquals(listOf("kind"), added.map { it.username })
        assertEquals(listOf(fabian), store.aside.value.map { it.account })
        assertTrue(signOut.switched.isEmpty())
    }

    @Test fun addProfile_ssoFailing_theOldAccountGoesOn() {
        signedInAsFabian()
        oidc.finishResult = { throw OidcException(OidcException.Reason.UNREACHABLE) }
        val v = addVm()
        bus.post(OidcCallback("c", "s", null))
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        assertEquals(fabian, store.acc.value)
        assertTrue(added.isEmpty())
    }

    /** The form belongs to the pages of the account before: it goes the moment the account changes. */
    @Test fun aSignIn_thePageGoingAwayAtThatMoment_stillStartsTheSync() {
        signedInAsFabian()
        lateinit var v: SetupViewModel
        val finished = CompletableDeferred<Unit>()
        v = viewModels.own(
            SetupViewModel(store, login, net, sync, oidc, bus, signOut, addProfile = { a, acc, ref ->
                store.add(a, acc, ref)
                v.viewModelScope.cancel()
                finished.await()
            }, mode = SetupMode.ADD_PROFILE),
        )
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        finished.complete(Unit)
        assertEquals(1, sync.count)
    }

    /** Review focus 5: back on an account whose session ran out; the form asks for exactly that one. */
    @Test fun reauth_afterASwitch_asksForTheAccountThatIsActiveNow() {
        val theirs = ServerAddresses(null, "https://kind.example.org".toHttpUrl())
        val kind = Account(Account.computeServerId(theirs, "u2"), "u2", "kind", false, theirs, emptySet())
        signedInAsFabian()
        store.aside.value = listOf(FakeAccountStore.Aside(kind, "a1", "r1", reauth = true))
        kotlinx.coroutines.runBlocking { store.activate(kind.serverId) }
        assertTrue(store.reauth.value)

        login.behaviour = { LoginResponse(AbsUser("u2", "kind", "user", "new-acc", "new-ref")) }
        val v = vm()
        assertEquals("kind", v.state.value.username)
        assertEquals("https://kind.example.org", v.state.value.public)
        v.onPasswordChanged("secret")
        v.login()
        assertEquals(kind.serverId, store.acc.value!!.serverId)
        assertEquals("new-acc", store.access)
        assertFalse(store.reauth.value)
        assertEquals(listOf(fabian), store.aside.value.map { it.account }) // the other profile is untouched
        assertTrue(signOut.switched.isEmpty())
    }

    /** Review M1: the attempt started as one more profile and fails on a form that opened as a plain one. */
    @Test fun addProfile_ssoFailingOnAPlainForm_theFormAddsToo_aRetryKeepsTheOldAccount() {
        signedInAsFabian()
        oidc.pendingAdd = true
        oidc.finishResult = { throw OidcException(OidcException.Reason.UNREACHABLE) }
        val v = addVm(SetupMode.SIGN_IN)
        bus.post(OidcCallback("c", "s", null))
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        assertTrue(v.state.value.addProfile)

        login.behaviour = { LoginResponse(AbsUser("u9", "kind", "user", "acc", "ref")) }
        v.fill(public = "https://abs.example.org", user = "kind")
        v.login()
        assertEquals(listOf("kind"), added.map { it.username })
        assertEquals(listOf(fabian), store.aside.value.map { it.account })
        assertTrue("the old account is not signed out", signOut.switched.isEmpty())
    }

    @Test fun signIn_aSsoAttemptThatWasNoAdd_doesNotMakeTheFormOne() {
        signedInAsFabian()
        oidc.finishResult = { throw OidcException(OidcException.Reason.UNREACHABLE) }
        val v = vm()
        bus.post(OidcCallback("c", "s", null))
        assertFalse(v.state.value.addProfile)
    }

    /** From the device pass: opened from „Wer hört zu?“ nobody is active, so nobody "stays signed in". */
    @Test fun addProfile_knowsWhetherSomeoneIsActive() {
        signedInAsFabian()
        assertEquals(false, addVm().state.value.nobodyActive)
        store.acc.value = null
        store.aside.value = listOf(FakeAccountStore.Aside(fabian, "a0", "r0", false))
        assertEquals(true, addVm().state.value.nobodyActive)
    }

    // --- 3.6, a forgotten PIN ---

    private val kindAtHome = Account(
        Account.computeServerId(ServerAddresses("http://nas.lan:13378".toHttpUrl(), "https://abs.example.org".toHttpUrl(), setOf("HomeNet")), "u9"),
        "u9", "kind", false, ServerAddresses("http://nas.lan:13378".toHttpUrl(), "https://abs.example.org".toHttpUrl(), setOf("HomeNet")), setOf("http://nas.lan:13378"),
    )
    private val unlocked = mutableListOf<Account>()
    private val ended = mutableListOf<String>()
    private var unlockFails = false

    private fun unlockVm(target: String? = kindAtHome.serverId, mode: SetupMode = SetupMode.UNLOCK) = viewModels.own(
        SetupViewModel(
            store, login, net, sync, oidc, bus, signOut, addProfile = { a, _, _ -> added += a }, mode = mode, unlockTarget = target,
            unlock = { a, acc, ref ->
                if (unlockFails) throw IOException("disk full")
                unlocked += a
                store.add(a, acc, ref)
            },
            endSession = { a, _ -> ended += a.username },
        ),
    )

    private fun kindIsSaved() {
        signedInAsFabian()
        store.aside.value = listOf(FakeAccountStore.Aside(kindAtHome, "old-a", "old-r", false))
    }

    private fun answersAs(id: String, name: String) {
        login.behaviour = { LoginResponse(AbsUser(id, name, "user", "new-a", "new-r")) }
    }

    @Test fun unlock_theFormIsThatAccounts_butThePassword() {
        kindIsSaved()
        val s = unlockVm().state.value
        assertEquals(kindAtHome.serverId, s.unlockServerId)
        assertEquals("kind", s.unlockName)
        assertEquals("kind", s.username)
        assertEquals("http://nas.lan:13378", s.local)
        assertEquals("https://abs.example.org", s.public)
        assertEquals("", s.password)
        assertTrue(s.ssidBinding)
        assertEquals(setOf("HomeNet"), s.keptSsids)
        assertFalse(s.addProfile)
        assertTrue(s.addressesFixed)
    }

    /** The form of the active profile, when it was its own PIN that was forgotten at „Profil schützen“. */
    @Test fun unlock_alsoForTheActiveProfile() {
        store.acc.value = kindAtHome
        assertEquals("kind", unlockVm().state.value.unlockName)
    }

    /** No such account (any more): nothing can be sent. */
    @Test fun unlock_withoutThatAccount_theFormStaysEmpty() {
        signedInAsFabian()
        val s = unlockVm().state.value
        assertEquals("", s.local)
        assertEquals("", s.public)
        assertFalse(s.copy(username = "x", password = "y").canLogin)
    }

    /** Review focus 3: another server could answer anything with the account's user id. */
    @Test fun unlock_theAddressesCannotBeChanged() {
        kindIsSaved()
        val v = unlockVm()
        v.onLocalChanged("http://evil.lan")
        v.onPublicChanged("https://evil.example")
        assertEquals("http://nas.lan:13378", v.state.value.local)
        assertEquals("https://abs.example.org", v.state.value.public)
    }

    @Test fun unlock_exactlyThisAccount_comesIn_notAsAnAddNotAsASignIn() {
        kindIsSaved()
        answersAs("u9", "kind")
        val v = unlockVm()
        v.onPasswordChanged("secret")
        v.login()
        assertEquals(listOf("kind"), unlocked.map { it.username })
        assertEquals(kindAtHome.serverId, unlocked.single().serverId)
        assertTrue(added.isEmpty())
        assertTrue(signOut.switched.isEmpty())
        assertTrue(v.state.value.done)
        assertEquals("", v.state.value.password)
        assertEquals(1, sync.count)
    }

    /** Task 3.6: refused and nothing changes; the session it has just got on the server does not stay behind. */
    @Test fun unlock_anotherAccount_isRefused_andChangesNothing() {
        kindIsSaved()
        answersAs("u1", "fabian")
        val v = unlockVm()
        v.onUsernameChanged("fabian")
        v.onPasswordChanged("secret")
        v.login()
        assertEquals(SetupError.UNLOCK_OTHER_ACCOUNT, v.state.value.error)
        assertFalse(v.state.value.done)
        assertFalse(v.state.value.loading)
        assertTrue(unlocked.isEmpty())
        assertTrue(added.isEmpty())
        assertTrue(signOut.switched.isEmpty())
        assertEquals(fabian, store.acc.value)
        assertEquals("a0", store.access)
        assertEquals("old-a", store.aside.value.single().access)
        assertEquals(0, sync.count)
        assertEquals(listOf("fabian"), ended)
    }

    @Test fun unlock_aWrongPassword_changesNothing() {
        kindIsSaved()
        login.behaviour = { throw HttpException(Response.error<Any>(401, "".toResponseBody())) }
        val v = unlockVm()
        v.onPasswordChanged("wrong")
        v.login()
        assertEquals(SetupError.WRONG_LOGIN, v.state.value.error)
        assertTrue(unlocked.isEmpty())
        assertEquals(fabian, store.acc.value)
    }

    @Test fun unlock_whatCannotBeStored_saysSo() {
        kindIsSaved()
        answersAs("u9", "kind")
        unlockFails = true
        val v = unlockVm()
        v.onPasswordChanged("secret")
        v.login()
        assertEquals(SetupError.SAVE_FAILED, v.state.value.error)
        assertEquals(fabian, store.acc.value)
    }

    @Test fun unlock_theSsoAttemptCarriesWhoseItIs() {
        kindIsSaved()
        val v = unlockVm()
        v.loginWithSso()
        assertEquals(kindAtHome.serverId, oidc.lastFlow.unlockServerId)
        assertFalse(oidc.lastFlow.addProfile)
        assertEquals("the account's own addresses", listOf("https://abs.example.org/".toHttpUrl()), oidc.started.take(1))
    }

    private val unlockFlow get() = OidcFlowContext(
        "http://nas.lan:13378/", "https://abs.example.org/", true, "HomeNet", setOf("HomeNet"), unlockServerId = kindAtHome.serverId,
    )

    /** Review focus 4 for this mode: back from the browser on a form of whatever mode, after a rotation or a process death. */
    @Test fun unlock_ssoBack_onAPlainForm_theAttemptDecides() {
        kindIsSaved()
        oidc.pendingUnlockOf = kindAtHome.serverId
        oidc.finishResult = { OidcResult("https://abs.example.org".toHttpUrl(), LoginResponse(AbsUser("u9", "kind", "user", "new-a", "new-r")), unlockFlow) }
        val v = unlockVm(target = null, mode = SetupMode.SIGN_IN)
        bus.post(OidcCallback("c", "s", null))
        assertEquals(kindAtHome.serverId, unlocked.single().serverId)
        assertEquals(setOf("HomeNet"), unlocked.single().addresses.homeSsids)
        assertTrue(v.state.value.done)
        assertTrue("not signed out", signOut.switched.isEmpty())
    }

    @Test fun unlock_ssoBack_anotherAccount_isRefused_onAPlainFormToo() {
        kindIsSaved()
        oidc.pendingUnlockOf = kindAtHome.serverId
        oidc.finishResult = { OidcResult("https://abs.example.org".toHttpUrl(), LoginResponse(AbsUser("u1", "fabian", "user", "new-a", "new-r")), unlockFlow) }
        val v = unlockVm(target = null, mode = SetupMode.SIGN_IN)
        bus.post(OidcCallback("c", "s", null))
        assertEquals(SetupError.UNLOCK_OTHER_ACCOUNT, v.state.value.error)
        assertTrue(unlocked.isEmpty())
        assertTrue(signOut.switched.isEmpty())
        assertEquals(fabian, store.acc.value)
        assertEquals(listOf("fabian"), ended)
    }

    /** M1 of Task 2, for this mode: the attempt fails on a plain form; the second try is still about that account, and only that. */
    @Test fun unlock_ssoFailingOnAPlainForm_theFormBecomesThatAccounts() {
        kindIsSaved()
        oidc.pendingUnlockOf = kindAtHome.serverId
        oidc.finishResult = { throw OidcException(OidcException.Reason.UNREACHABLE) }
        val v = unlockVm(target = null, mode = SetupMode.SIGN_IN)
        bus.post(OidcCallback("c", "s", null))
        assertEquals(SetupError.UNREACHABLE, v.state.value.error)
        assertEquals(kindAtHome.serverId, v.state.value.unlockServerId)
        assertEquals("https://abs.example.org", v.state.value.public)
        assertTrue(v.state.value.addressesFixed)

        answersAs("u1", "fabian")
        v.onUsernameChanged("fabian")
        v.onPasswordChanged("secret")
        v.login()
        assertEquals(SetupError.UNLOCK_OTHER_ACCOUNT, v.state.value.error)
        assertTrue(signOut.switched.isEmpty())
        assertEquals(fabian, store.acc.value)
    }

    /** A form of this mode gets the answer of an older attempt that was something else: that one decides, and takes off nothing. */
    @Test fun unlock_theFormDoesNotMakeAnOlderAttemptAnUnlock() {
        kindIsSaved()
        oidc.finishResult = { OidcResult("https://abs.example.org".toHttpUrl(), LoginResponse(AbsUser("u5", "oma", "user", "a", "r")), OidcFlowContext(null, "https://abs.example.org/", addProfile = true)) }
        unlockVm()
        bus.post(OidcCallback("c", "s", null))
        assertTrue(unlocked.isEmpty())
        assertEquals(listOf("oma"), added.map { it.username })
    }

    // --- Task 3, C1: when a session at the provider is no proof ---

    private fun ssoFrom(v: SetupViewModel): Boolean {
        v.loginWithSso()
        return oidc.lastFlow.freshLogin
    }

    @Test fun sso_theFirstSignIn_alone_asksNothingNew() {
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        assertFalse(ssoFrom(v))
    }

    @Test fun sso_aReauth_ofTheOnlyAccount_asksNothingNew() {
        signedInAsFabian()
        assertFalse(ssoFrom(vm()))
    }

    @Test fun sso_addingAProfile_asksForAFreshLogin() {
        signedInAsFabian()
        val v = addVm()
        v.onPublicChanged("https://abs.example.org")
        assertTrue(ssoFrom(v))
    }

    /** The picker's form has nobody active. */
    @Test fun sso_addingAProfile_fromThePicker_asksForAFreshLogin() {
        store.aside.value = listOf(FakeAccountStore.Aside(fabian, "a", "r", false))
        assertTrue(ssoFrom(addVm()))
    }

    @Test fun sso_aForgottenPin_asksForAFreshLogin() {
        kindIsSaved()
        assertTrue(ssoFrom(unlockVm()))
    }

    /** Nobody else on the device: its own PIN, forgotten at „Profil schützen“. The way still takes a protection off. */
    @Test fun sso_aForgottenPin_ofTheOnlyProfile_asksForAFreshLogin() {
        store.acc.value = kindAtHome
        assertTrue(ssoFrom(unlockVm()))
    }

    @Test fun sso_aReauth_withOtherProfilesOnTheDevice_asksForAFreshLogin() {
        kindIsSaved()
        assertTrue(ssoFrom(vm()))
    }

    @Test fun sso_aSignIn_withNobodyActive_butProfilesThere_asksForAFreshLogin() {
        store.aside.value = listOf(FakeAccountStore.Aside(kindAtHome, "a", "r", false))
        val v = vm()
        v.onPublicChanged("https://abs.example.org")
        assertTrue(ssoFrom(v))
    }

    /** It cannot be told who else is on the device: the stricter way. */
    @Test fun sso_profilesThatCannotBeRead_asksForAFreshLogin() {
        val broken = object : io.wolftown.lauschig.account.AccountStore by store {
            override val saved: Flow<List<Account>> = kotlinx.coroutines.flow.flow { throw IOException("unreadable") }
        }
        val v = viewModels.own(SetupViewModel(broken, login, net, sync, oidc, bus, signOut))
        v.onPublicChanged("https://abs.example.org")
        assertTrue(ssoFrom(v))
    }

    /** Task 2's re-review: until it is read, the form does not claim that someone stays signed in. */
    @Test fun addProfile_whoIsActive_isNotKnownBeforeItIsRead() {
        val read = CompletableDeferred<Account?>()
        val slow = object : io.wolftown.lauschig.account.AccountStore by store {
            override val account: Flow<Account?> = kotlinx.coroutines.flow.flow { emit(read.await()) }
        }
        val v = viewModels.own(SetupViewModel(slow, login, net, sync, oidc, bus, signOut, mode = SetupMode.ADD_PROFILE))
        assertEquals(null, v.state.value.nobodyActive)
        read.complete(null)
        assertEquals(true, v.state.value.nobodyActive)
    }

    // Server addresses from a QR code (docs/server-qr-code.md): a scan fills the two address fields, as if typed, and
    // does nothing else. Nobody is signed in by it.

    private val serverCode = "lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378&public=https%3A%2F%2Fabs.example.org%2Faudiobookshelf"

    @Test fun aScannedCode_fillsTheTwoAddressFields_andNothingElse() {
        val v = vm()
        v.onUsernameChanged("fabian"); v.onPasswordChanged("secret")
        val before = v.state.value
        v.onQrScanned(serverCode)
        assertEquals(
            before.copy(local = "http://192.168.1.20:13378", public = "https://abs.example.org/audiobookshelf", scanNote = SetupScanNote.Filled),
            v.state.value,
        )
    }

    @Test fun aScannedCode_signsNobodyIn_evenWithACompleteForm() {
        val v = vm()
        v.fill(public = "https://old.example.org")
        v.onQrScanned(serverCode)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue("the form could be sent", v.state.value.canLogin)
        assertEquals("but nobody sent it", emptyList<HttpUrl>(), login.calls)
        assertFalse(v.state.value.loading)
        assertFalse(v.state.value.done)
        assertNull(store.acc.value)
        assertEquals(0, sync.count)
        assertEquals(emptyList<HttpUrl>(), oidc.started)
    }

    @Test fun aScannedCode_withOneAddress_emptiesTheOtherField() {
        val v = vm()
        v.fill(local = "http://192.168.1.5:13378", public = "https://old.example.org")
        v.onQrScanned("lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org")
        assertEquals("", v.state.value.local)
        assertEquals("https://abs.example.org", v.state.value.public)
    }

    @Test fun scannedAddresses_areCheckedLikeTypedOnes_forSso() {
        val asked = mutableListOf<HttpUrl>()
        login.statusResult = { asked += it; StatusResponse("2.37.1", listOf("local", "openid"), AuthFormData(authOpenIDButtonText = "Mit Authentik")) }
        val v = vm()
        v.onQrScanned("lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("https://abs.example.org/".toHttpUrl()), asked)
        assertTrue(v.state.value.ssoAvailable)
    }

    @Test fun aScannedHttpAddress_isNotAskedBeforeTheUsersOk_andLoginStillAsksForIt() {
        val asked = mutableListOf<HttpUrl>()
        login.statusResult = { asked += it; throw IOException("no status") }
        val v = vm()
        v.onQrScanned("lauschig://server?v=1&local=http%3A%2F%2F192.168.1.20%3A13378")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("plain http is asked nothing before the confirmation", emptyList<HttpUrl>(), asked)
        v.onUsernameChanged("fabian"); v.onPasswordChanged("secret")
        v.login()
        assertEquals(listOf("http://192.168.1.20:13378"), v.state.value.cleartextPrompt)
        assertEquals(emptyList<HttpUrl>(), login.calls)
    }

    @Test fun aRefusedCode_changesNoField_andSaysWhy() {
        val v = vm()
        v.fill(local = "http://192.168.1.5:13378", public = "https://old.example.org")
        val refused = listOf(
            "https://example.org/" to ServerQrError.NOT_A_SERVER_CODE,
            "lauschig://server?v=2&public=https%3A%2F%2Fabs.example.org" to ServerQrError.UNKNOWN_VERSION,
            "lauschig://server?v=1" to ServerQrError.NO_ADDRESS,
            "lauschig://server?v=1&public=ftp%3A%2F%2Fabs.example.org" to ServerQrError.BAD_ADDRESS,
            "lauschig://server?v=1&public=https%3A%2F%2Ffabi%3Ageheim%40abs.example.org" to ServerQrError.CREDENTIALS,
            "lauschig://server?v=1&public=https%3A%2F%2Fabs.example.org&x=" + "a".repeat(ServerQr.MAX_LENGTH) to ServerQrError.TOO_LONG,
        )
        for ((text, reason) in refused) {
            v.onQrScanned(text)
            assertEquals(text.take(60), SetupScanNote.Refused(reason), v.state.value.scanNote)
            assertEquals("http://192.168.1.5:13378", v.state.value.local)
            assertEquals("https://old.example.org", v.state.value.public)
        }
        assertEquals(emptyList<HttpUrl>(), login.calls)
    }

    @Test fun typingAnAddress_takesTheScanNoteAway() {
        val v = vm()
        v.onQrScanned(serverCode)
        assertEquals(SetupScanNote.Filled, v.state.value.scanNote)
        v.onPublicChanged("https://abs.example.org/audiobookshelf2")
        assertNull(v.state.value.scanNote)
        v.onQrScanned("nothing")
        assertEquals(SetupScanNote.Refused(ServerQrError.NOT_A_SERVER_CODE), v.state.value.scanNote)
        v.onLocalChanged("http://192.168.1.5:13378")
        assertNull(v.state.value.scanNote)
    }

    @Test fun aRefusedCamera_saysSo_andChangesNoField() {
        val v = vm()
        v.fill(public = "https://old.example.org")
        val before = v.state.value
        v.onCameraRefused()
        assertEquals(before.copy(scanNote = SetupScanNote.CameraRefused), v.state.value)
    }

    /** Found on the tablet: "no camera access" stayed on the form after the camera was allowed on a second try. */
    @Test fun openingTheCamera_takesAnOlderNoteAway_andChangesNoField() {
        val v = vm()
        v.fill(public = "https://old.example.org")
        val before = v.state.value
        v.onCameraRefused()
        v.onScanStarted()
        assertEquals(before, v.state.value)
        v.onQrScanned("nothing")
        v.onScanStarted()
        assertEquals(before, v.state.value)
    }

    @Test fun onTheWayOfAForgottenPin_aScanChangesNothing() {
        signedInAsFabian()
        store.aside.value = listOf(FakeAccountStore.Aside(kindAtHome, "a", "r", false))
        val v = unlockVm()
        dispatcher.scheduler.advanceUntilIdle()
        val before = v.state.value
        assertTrue(before.addressesFixed)
        v.onQrScanned(serverCode)
        assertEquals(before, v.state.value)
    }

    /** The whole way: one device's account → the code it shows → a camera frame of it → the other device's form. */
    @Test fun fromOneDevicesAccount_throughThePicture_intoTheOtherDevicesForm() {
        val shown = ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/audiobookshelf/".toHttpUrl(), setOf("Heimnetz"))
        val picture = qrFrame(QrCode.modules(ServerQr.code(shown)!!.text))
        val seen = QrFrameDecoder().decode(picture)!!
        val v = vm()
        v.onQrScanned(seen)
        assertEquals("http://192.168.1.20:13378", v.state.value.local)
        assertEquals("https://abs.example.org/audiobookshelf", v.state.value.public)
        assertEquals("only where the server is: no name, no Wi-Fi", SetupUiState(local = v.state.value.local, public = v.state.value.public, scanNote = SetupScanNote.Filled), v.state.value)
    }

    // --- Setting up the TV with the phone: the handed-over session is exchanged and saved like any sign-in.

    private fun handover(local: String? = "http://192.168.1.20:13378/", public: String? = "https://abs.example.org/") =
        PhoneHandover(local = local, public = public, refresh = "phone-ref")

    private fun refused(code: Int): (HttpUrl) -> LoginResponse = { throw HttpException(Response.error<Any>(code, "".toResponseBody(null))) }

    @Test fun phone_savesTheHandedOverAddresses_andTheTokensOfTheAnswer() = runBlocking {
        val v = vm()
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover()))
        assertEquals(listOf("http://192.168.1.20:13378/".toHttpUrl() to "phone-ref"), login.refreshes)
        val a = store.acc.value!!
        assertEquals("http://192.168.1.20:13378/".toHttpUrl(), a.addresses.local)
        assertEquals("https://abs.example.org/".toHttpUrl(), a.addresses.public)
        assertEquals("tv-acc", store.access)
        assertEquals("tv-ref", store.refresh)
        assertEquals(1, sync.count)
        assertTrue(v.state.value.done)
        assertTrue("no password login", login.calls.isEmpty())
    }

    @Test fun phone_aPlainHttpAddress_countsAsConfirmed() = runBlocking {
        val v = vm()
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover()))
        assertNull("no question on the TV: the person saw it on the phone", v.state.value.cleartextPrompt)
        assertEquals(setOf("http://192.168.1.20:13378/".toHttpUrl().cleartextKey()), store.acc.value!!.allowCleartext)
    }

    @Test fun phone_localDead_thePublicAddressAnswers() = runBlocking {
        login.refreshBehaviour = { if (it.host == "192.168.1.20") throw IOException("timeout") else ok(access = "tv-acc", refresh = "tv-ref") }
        val v = vm()
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover()))
        assertEquals(listOf("192.168.1.20", "abs.example.org"), login.refreshes.map { it.first.host })
        assertEquals("tv-ref", store.refresh)
        assertTrue(v.state.value.done)
    }

    @Test fun phone_refused_isFinal_andNothingIsSaved() = runBlocking {
        for (code in listOf(401, 403)) {
            login.refreshes.clear()
            login.refreshBehaviour = refused(code)
            val v = vm()
            assertEquals(HandoverReply.SIGN_IN_REFUSED, v.signInFromPhone(handover()))
            assertEquals("the public address is not tried", 1, login.refreshes.size)
            assertNull(store.acc.value)
            assertFalse(v.state.value.done)
            assertNull("the form shows nothing of it", v.state.value.error)
        }
    }

    @Test fun phone_noAddressAnswers_serverUnreachable() = runBlocking {
        login.refreshBehaviour = { if (it.host == "192.168.1.20") throw IOException("timeout") else refused(500)(it) }
        val v = vm()
        assertEquals(HandoverReply.SERVER_UNREACHABLE, v.signInFromPhone(handover()))
        assertEquals(2, login.refreshes.size)
        assertNull(store.acc.value)
        assertFalse(v.state.value.loading)
        assertNull(v.state.value.error)
    }

    @Test fun phone_serverTooOld_failed() = runBlocking {
        login.refreshBehaviour = { ok(access = null, refresh = null) }
        val v = vm()
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover(local = null)))
        assertNull(store.acc.value)
        assertFalse(v.state.value.done)
        assertNull(v.state.value.error)
    }

    @Test fun phone_cannotBeSaved_failed() = runBlocking {
        signedInAsFabian()
        addFails = IOException("disk full")
        val v = addVm()
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover()))
        assertEquals(fabian, store.acc.value)
        assertEquals(0, sync.count)
        assertFalse(v.state.value.done)
    }

    @Test fun phone_addProfile_takesTheWayOfAnotherProfile() = runBlocking {
        signedInAsFabian()
        login.refreshBehaviour = { LoginResponse(AbsUser("u9", "kind", "user", "tv-acc", "tv-ref")) }
        val v = addVm()
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover()))
        assertEquals(listOf("kind"), added.map { it.username })
        assertEquals(listOf(fabian), store.aside.value.map { it.account })
        assertTrue("no sign-out of the old account", signOut.switched.isEmpty())
        assertTrue(v.state.value.done)
    }

    @Test fun phone_onTheWayOfAForgottenPin_failsWithoutARequest() = runBlocking {
        kindIsSaved()
        val v = unlockVm()
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover()))
        assertTrue(login.refreshes.isEmpty())
        assertTrue(unlocked.isEmpty())
        assertFalse(v.state.value.done)
    }

    @Test fun phone_anAddressThatIsNoUrl_failsWithoutARequest() = runBlocking {
        val v = vm()
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover(local = "not an address")))
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover(local = null, public = null)))
        assertTrue(login.refreshes.isEmpty())
        assertNull(store.acc.value)
    }

    /**
     * Review focus 5: the TV leaves the page the moment `done` is set, and the page's scope (the listener's) goes with it.
     * Nothing may suspend between that and the answer, or the phone would hear "failed" from a TV that is signed in.
     */
    @Test fun phone_theCallerCancelledTheMomentItIsDone_stillAnswersSignedIn() = runBlocking {
        val v = vm()
        var reply: HandoverReply? = null
        val caller = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { reply = v.signInFromPhone(handover()) }
        // Unconfined: runs inside the very update that sets `done`, as early as any navigation could.
        val watcher = launch(Dispatchers.Unconfined) {
            v.state.first { it.done }
            caller.cancel()
        }
        caller.start()
        caller.join()
        watcher.join()
        assertTrue(caller.isCancelled)
        assertEquals(HandoverReply.SIGNED_IN, reply)
    }

    /** The save changes the active account, and on the TV the pages of the old one go at once, the caller's scope with them. */
    @Test fun phone_theCallerCancelledDuringTheSave_stillAnswersSignedIn() = runBlocking {
        signedInAsFabian()
        lateinit var caller: Job
        val v = viewModels.own(
            SetupViewModel(store, login, net, sync, oidc, bus, signOut, addProfile = { a, acc, ref ->
                store.add(a, acc, ref)
                caller.cancel()
                // A save that suspends after the switch, as the real one does.
                withContext(Dispatchers.Default) { yield() }
            }, mode = SetupMode.ADD_PROFILE),
        )
        var reply: HandoverReply? = null
        caller = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { reply = v.signInFromPhone(handover()) }
        caller.start()
        caller.join()
        assertEquals(HandoverReply.SIGNED_IN, reply)
        assertEquals(1, sync.count)
    }

    /** Home and back (or "New code") starts another listener while a sign-in still runs: a second packet must not start a second one. */
    @Test fun phone_oneHandoverAtATime() = runBlocking {
        val v = vm()
        login.refreshGate = CompletableDeferred()
        var first: HandoverReply? = null
        val running = launch(start = CoroutineStart.UNDISPATCHED) { first = v.signInFromPhone(handover(local = null)) }
        assertEquals(1, login.refreshes.size)
        assertEquals(HandoverReply.FAILED, v.signInFromPhone(handover(local = null)))
        assertEquals("no request for the second", 1, login.refreshes.size)
        login.refreshGate!!.complete(Unit)
        running.join()
        assertEquals(HandoverReply.SIGNED_IN, first)
        assertEquals("done: no further sign-in", HandoverReply.FAILED, v.signInFromPhone(handover(local = null)))
        assertEquals(1, login.refreshes.size)
    }

    @Test fun phone_afterAFailedHandover_aNewOneWorks() = runBlocking {
        login.refreshBehaviour = { throw IOException("timeout") }
        val v = vm()
        assertEquals(HandoverReply.SERVER_UNREACHABLE, v.signInFromPhone(handover(local = null)))
        login.refreshBehaviour = { ok(access = "tv-acc", refresh = "tv-ref") }
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover(local = null)))
        assertEquals(2, login.refreshes.size)
    }

    @Test fun phone_afterACancelledHandover_aNewOneWorks() = runBlocking {
        val v = vm()
        login.refreshGate = CompletableDeferred()
        val running = launch(start = CoroutineStart.UNDISPATCHED) { v.signInFromPhone(handover(local = null)) }
        running.cancel()
        running.join()
        login.refreshGate = null
        assertEquals(HandoverReply.SIGNED_IN, v.signInFromPhone(handover(local = null)))
    }
}
