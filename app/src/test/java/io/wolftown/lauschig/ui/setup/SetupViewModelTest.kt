package io.wolftown.lauschig.ui.setup

import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.account.Account
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
    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse {
        calls += baseUrl
        return behaviour(baseUrl)
    }
}

private class FakeSync : SyncTrigger {
    var count = 0
    override fun syncNow() { count++ }
}

private class FakeNet(var ssid: String? = null) : NetworkInfo {
    override val isConnected: Flow<Boolean> = flowOf(true)
    override suspend fun currentSsid() = ssid
}

private fun ok(access: String? = "acc", refresh: String? = "ref", type: String = "user") =
    LoginResponse(AbsUser("u1", "fabian", type, access, refresh))

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = FakeAccountStore()
    private val login = FakeLogin { ok() }
    private val sync = FakeSync()
    private val net = FakeNet()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = SetupViewModel(store, login, net, sync)

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
    }
}
