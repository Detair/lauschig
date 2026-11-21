package io.wolftown.lauschig.ui.tvsetup

import androidx.lifecycle.viewModelScope
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.absapi.auth.StatusResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.serverqr.ServerQr
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.tvsetup.SendResult
import io.wolftown.lauschig.tvsetup.TvSetupCode
import io.wolftown.lauschig.ui.setup.LoginClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

private class FakeLogin : LoginClient {
    val calls = mutableListOf<Pair<HttpUrl, LoginRequest>>()
    var behaviour: (HttpUrl) -> LoginResponse = { LoginResponse(AbsUser(USER_ID, "fabian", "user", "tv-access", TV_REFRESH)) }
    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse {
        calls += baseUrl to request
        return behaviour(baseUrl)
    }
    override suspend fun status(baseUrl: HttpUrl): StatusResponse = throw IOException("not asked")
    /** The phone's own exchange of the handed-over token, only to end the TV's session (spec E19). */
    val refreshes = mutableListOf<Pair<HttpUrl, String>>()
    /** Tokens a logout really deleted: the server refuses them from then on, like Audiobookshelf. */
    val loggedOut = mutableSetOf<String>()
    var refreshBehaviour: (String) -> LoginResponse = { token ->
        if (token in loggedOut) throw httpError(401)
        LoginResponse(AbsUser(USER_ID, "fabian", "user", "a2", CURRENT_REFRESH))
    }
    override suspend fun refresh(baseUrl: HttpUrl, refreshToken: String): LoginResponse {
        refreshes += baseUrl to refreshToken
        return refreshBehaviour(refreshToken)
    }
}

private const val USER_ID = "u1"
private const val TV_REFRESH = "tv-refresh-token"
private const val CURRENT_REFRESH = "current-refresh-token"
private val LOCAL = "http://192.168.1.20:13378/".toHttpUrl()
private val PUBLIC = "https://abs.example.org/abs/".toHttpUrl()
private const val PASSWORD = "geheim-123"

private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

@OptIn(ExperimentalCoroutinesApi::class)
class TvHandoverViewModelTest {
    private val store = FakeAccountStore()
    private val login = FakeLogin()
    private val sent = mutableListOf<Pair<TvSetupCode, PhoneHandover>>()
    private var sendResult: suspend () -> SendResult = { SendResult.SIGNED_IN }
    /** Logouts: account, address, token. */
    private val ended = mutableListOf<Triple<Account, HttpUrl, String>>()
    private var logoutConfirms = true
    private var connection: ConnectionState = ConnectionState.Online(LOCAL, ConnectionState.Via.LOCAL)
    private var homeNetwork = true
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = TestViewModels()

    private val code = TvSetupCode("192.168.1.50", 41234, ByteArray(32) { it.toByte() })

    private val account = Account(
        serverId = "s1", userId = USER_ID, username = "fabian", isAdmin = false,
        addresses = ServerAddresses(LOCAL, PUBLIC),
        allowCleartext = setOf(LOCAL.cleartextKey()),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store.acc.value = account
    }

    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(
        TvHandoverViewModel(
            store, login,
            send = { c, h -> sent += c to h; sendResult() },
            logoutAt = { a, url, r ->
                ended += Triple(a, url, r)
                // A logout that "worked" deletes the session; one that did not leaves it, though the server says 200 anyway.
                if (logoutConfirms) login.loggedOut += r
                true
            },
            connection = { connection },
            hasHomeNetwork = { homeNetwork },
        ),
    )

    private fun TvHandoverViewModel.scanned(text: String = code.text) {
        startScan()
        onScanned(text)
    }

    private fun TvHandoverViewModel.typedAndSent(password: String = PASSWORD) {
        scanned()
        onPassword(password)
        send()
    }

    // --- The scan ---

    @Test fun startsWithTheExplanation_thenTheCamera() {
        val v = vm()
        assertEquals(TvHandoverStep.Intro(), v.step.value)
        v.startScan()
        assertEquals(TvHandoverStep.Scanning, v.step.value)
        v.scanClosed()
        assertEquals(TvHandoverStep.Intro(), v.step.value)
    }

    @Test fun refusedCamera_isNoted() {
        val v = vm()
        v.cameraRefused()
        assertEquals(TvHandoverStep.Intro(TvHandoverNote.CAMERA_REFUSED), v.step.value)
    }

    @Test fun aTvCode_asksToConfirm_withTheNumberTheAccountAndItsAddresses_localFirst() {
        val v = vm()
        v.scanned()
        val step = v.step.value as TvHandoverStep.Confirm
        assertEquals(code.number, step.number)
        assertEquals("fabian", step.username)
        assertEquals(listOf("http://192.168.1.20:13378", "https://abs.example.org/abs"), step.servers)
        assertEquals("", step.password)
        assertTrue("nothing is sent by a scan", sent.isEmpty() && login.calls.isEmpty())
    }

    @Test fun theServerCode_isNamedAsSuch() {
        val v = vm()
        v.scanned(ServerQr.text(account.addresses)!!)
        assertEquals(TvHandoverStep.Intro(TvHandoverNote.SERVER_CODE), v.step.value)
    }

    @Test fun anotherVersion_isNamedAsSuch() {
        val v = vm()
        v.scanned(code.text.replace("v=1", "v=2"))
        assertEquals(TvHandoverStep.Intro(TvHandoverNote.OTHER_VERSION), v.step.value)
    }

    @Test fun anAddressOutsideTheHomeNetwork_isNamedAsSuch() {
        val v = vm()
        v.scanned(code.text.replace("a=192.168.1.50", "a=8.8.8.8"))
        assertEquals(TvHandoverStep.Intro(TvHandoverNote.NOT_HOME_NETWORK), v.step.value)
    }

    @Test fun everythingElse_isNotATvCode() {
        for (text in listOf("https://example.org", "WIFI:S:home;T:WPA;P:x;;", code.text.replace("p=41234", "p=80"), "lauschig://tv?v=1&" + "x".repeat(300))) {
            val v = vm()
            v.scanned(text)
            assertEquals(text, TvHandoverStep.Intro(TvHandoverNote.NOT_A_TV_CODE), v.step.value)
        }
    }

    @Test fun aNewScan_dropsTheLastNote() {
        val v = vm()
        v.scanned("https://example.org")
        v.startScan()
        v.scanClosed()
        assertEquals(TvHandoverStep.Intro(), v.step.value)
    }

    // --- Confirm and send ---

    private fun assertNoSession() {
        assertTrue("nothing sent", sent.isEmpty())
        assertTrue("no session ended", ended.isEmpty())
        assertTrue("no exchange", login.refreshes.isEmpty())
    }

    @Test fun nothingIsSent_withoutAPassword() {
        val v = vm()
        v.scanned()
        v.send()
        assertTrue(login.calls.isEmpty())
        assertNoSession()
    }

    @Test fun success_signsInWhereTheAppIsConnected_sendsTheAccountsAddressesAndTheNewToken_andEndsNothing() {
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(LOCAL), login.calls.map { it.first })
        assertEquals(LoginRequest("fabian", PASSWORD), login.calls.single().second)
        val (to, handover) = sent.single()
        assertEquals(code, to)
        assertEquals(PhoneHandover(local = "http://192.168.1.20:13378", public = "https://abs.example.org/abs", refresh = TV_REFRESH), handover)
        assertEquals(TvHandoverStep.Done, v.step.value)
        assertTrue("no exchange and no logout after the TV signed in", ended.isEmpty() && login.refreshes.isEmpty())
    }

    /** E20: the password goes to the address the app is online through, even though a local one exists. */
    @Test fun onlineViaPublic_signsInAtThePublicAddressOnly() {
        connection = ConnectionState.Online(PUBLIC, ConnectionState.Via.PUBLIC)
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(PUBLIC), login.calls.map { it.first })
        assertEquals(TvHandoverStep.Done, v.step.value)
    }

    @Test fun onlineViaLocal_aFailedLogin_doesNotFallBackToThePublicAddress() {
        login.behaviour = { throw IOException("no route") }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(LOCAL), login.calls.map { it.first })
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, (v.step.value as TvHandoverStep.Confirm).error)
        assertNoSession()
    }

    @Test fun offline_noLogin_serverUnreachable() {
        connection = ConnectionState.Offline
        val v = vm()
        v.typedAndSent()
        assertTrue(login.calls.isEmpty())
        val step = v.step.value as TvHandoverStep.Confirm
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, step.error)
        assertEquals("", step.password)
        assertNoSession()
    }

    @Test fun onlineAtAnotherServer_countsAsOffline() {
        connection = ConnectionState.Online("https://other.example.org/".toHttpUrl(), ConnectionState.Via.PUBLIC)
        val v = vm()
        v.typedAndSent()
        assertTrue(login.calls.isEmpty())
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, (v.step.value as TvHandoverStep.Confirm).error)
    }

    @Test fun anUnconfirmedHttpConnection_neverGetsThePassword() {
        store.acc.value = account.copy(allowCleartext = emptySet())
        val v = vm()
        v.typedAndSent()
        assertTrue(login.calls.isEmpty())
        assertNoSession()
    }

    @Test fun noHomeNetwork_noLoginAtAll() {
        homeNetwork = false
        val v = vm()
        v.typedAndSent()
        assertTrue(login.calls.isEmpty())
        val step = v.step.value as TvHandoverStep.Confirm
        assertEquals(TvHandoverError.NO_HOME_NETWORK, step.error)
        assertEquals("", step.password)
        assertNoSession()
    }

    @Test fun wrongPassword_staysAtConfirm_sendsNothing_andEmptiesTheField() {
        for (status in listOf(401, 403)) {
            login.calls.clear()
            login.behaviour = { throw httpError(status) }
            val v = vm()
            v.typedAndSent()
            val step = v.step.value as TvHandoverStep.Confirm
            assertEquals(TvHandoverError.WRONG_PASSWORD, step.error)
            assertEquals("", step.password)
            assertFalse(step.working)
            assertEquals(1, login.calls.size)
            assertNoSession()
            // Typing again clears the error and may send again: the code is still the one scanned.
            v.onPassword("richtig")
            assertEquals(null, (v.step.value as TvHandoverStep.Confirm).error)
        }
    }

    @Test fun aServerError_isUnreachable_andSendsNothing() {
        login.behaviour = { throw httpError(500) }
        val v = vm()
        v.typedAndSent()
        assertEquals(1, login.calls.size)
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, (v.step.value as TvHandoverStep.Confirm).error)
        assertNoSession()
    }

    @Test fun anotherUserInTheAnswer_endsThatSession_andSendsNothing() {
        login.behaviour = { LoginResponse(AbsUser("someone-else", "fabian", "user", "a", "foreign-refresh")) }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(sent.isEmpty())
        assertEquals(listOf(LOCAL to "foreign-refresh", LOCAL to CURRENT_REFRESH), login.refreshes)
        assertEquals(listOf(Triple(account, LOCAL, CURRENT_REFRESH)), ended)
    }

    @Test fun anAnswerWithoutRefreshToken_sendsNothing_andHasNothingToEnd() {
        login.behaviour = { LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", null)) }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertNoSession()
    }

    @Test fun everyOtherReply_endsTheNewSessionExactlyOnce_andSaysWhy() {
        val expected = mapOf(
            SendResult.NOT_ACCEPTED to TvHandoverError.CODE_EXPIRED,
            SendResult.SERVER_UNREACHABLE to TvHandoverError.TV_CANNOT_REACH_SERVER,
            SendResult.SIGN_IN_REFUSED to TvHandoverError.TV_REFUSED,
            SendResult.DECLINED to TvHandoverError.DECLINED_ON_TV,
            SendResult.NO_HOME_NETWORK to TvHandoverError.NO_HOME_NETWORK,
            SendResult.NOT_SENT to TvHandoverError.TV_UNREACHABLE,
            SendResult.NO_REPLY to TvHandoverError.NO_REPLY,
            SendResult.FAILED to TvHandoverError.FAILED,
        )
        assertEquals("every result but SIGNED_IN is covered", SendResult.entries.toSet() - SendResult.SIGNED_IN, expected.keys)
        for ((result, error) in expected) {
            ended.clear()
            sent.clear()
            login.refreshes.clear()
            login.loggedOut.clear()
            sendResult = { result }
            val v = vm()
            v.typedAndSent()
            assertEquals(result.name, TvHandoverStep.Failed(error, sessionMayBeOpen = false), v.step.value)
            assertEquals(result.name, listOf(LOCAL to TV_REFRESH, LOCAL to CURRENT_REFRESH), login.refreshes)
            assertEquals(result.name, listOf(Triple(account, LOCAL, CURRENT_REFRESH)), ended)
            assertEquals(1, sent.size)
        }
    }

    /**
     * E19: a receiver that never exchanged the token: the phone exchanges it, logs out with the token it got back, and checks
     * that one: refused means gone.
     */
    @Test fun ending_exchangesFirst_thenLogsOutWithTheReturnedToken_andTheCheckConfirmsIt() {
        sendResult = { SendResult.NO_REPLY }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(LOCAL to TV_REFRESH, LOCAL to CURRENT_REFRESH), login.refreshes)
        assertEquals(listOf(Triple(account, LOCAL, CURRENT_REFRESH)), ended)
        assertEquals(TvHandoverStep.Failed(TvHandoverError.NO_REPLY, sessionMayBeOpen = false), v.step.value)
    }

    @Test fun ending_anExchangeRefused_stillLogsOutWithTheHandedToken_butMayBeOpen() {
        sendResult = { SendResult.NOT_ACCEPTED }
        login.refreshBehaviour = { throw httpError(401) }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(Triple(account, LOCAL, TV_REFRESH)), ended)
        assertEquals(TvHandoverStep.Failed(TvHandoverError.CODE_EXPIRED, sessionMayBeOpen = true), v.step.value)
    }

    @Test fun ending_serverUnreachable_stillTriesTheHandedToken_butMayBeOpen() {
        sendResult = { SendResult.NOT_ACCEPTED }
        login.refreshBehaviour = { throw IOException("gone") }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(Triple(account, LOCAL, TV_REFRESH)), ended)
        assertEquals(true, (v.step.value as TvHandoverStep.Failed).sessionMayBeOpen)
    }

    @Test fun ending_anExchangeWithoutToken_triesTheHandedToken_butMayBeOpen() {
        sendResult = { SendResult.DECLINED }
        login.refreshBehaviour = { LoginResponse(AbsUser(USER_ID, "fabian", "user", "a2", null)) }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(Triple(account, LOCAL, TV_REFRESH)), ended)
        assertEquals(TvHandoverStep.Failed(TvHandoverError.DECLINED_ON_TV, sessionMayBeOpen = true), v.step.value)
    }

    /** `/logout` answers 200 even when it deleted nothing: the check keeps finding the session alive, three rounds, then may be open. */
    @Test fun ending_aSessionThatStaysAlive_threeRounds_thenMayBeOpen() {
        sendResult = { SendResult.NOT_SENT }
        logoutConfirms = false
        val v = vm()
        v.typedAndSent()
        assertEquals(3, ended.size)
        assertEquals(4, login.refreshes.size)
        assertEquals(TvHandoverStep.Failed(TvHandoverError.TV_UNREACHABLE, sessionMayBeOpen = true), v.step.value)
    }

    /** Someone rotated in between: the check returns a new token once; that one is logged out and checked again. */
    @Test fun ending_theCheckFindsANewTokenOnce_logsOutThatOne_thenConfirmed() {
        sendResult = { SendResult.NOT_ACCEPTED }
        val rotated = "rotated-by-someone"
        login.refreshBehaviour = { token ->
            when (token) {
                TV_REFRESH -> LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", CURRENT_REFRESH))
                CURRENT_REFRESH -> if (CURRENT_REFRESH in login.loggedOut && rotated !in login.loggedOut) LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", rotated)) else throw httpError(401)
                else -> throw httpError(401)
            }
        }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(CURRENT_REFRESH, rotated), ended.map { it.third })
        assertEquals(TvHandoverStep.Failed(TvHandoverError.CODE_EXPIRED, sessionMayBeOpen = false), v.step.value)
    }

    @Test fun ending_theCheckUnreachable_mayBeOpen() {
        sendResult = { SendResult.NOT_ACCEPTED }
        login.refreshBehaviour = { token ->
            if (token == TV_REFRESH) LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", CURRENT_REFRESH)) else throw IOException("gone")
        }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf(CURRENT_REFRESH), ended.map { it.third })
        assertEquals(TvHandoverStep.Failed(TvHandoverError.CODE_EXPIRED, sessionMayBeOpen = true), v.step.value)
    }

    @Test fun ending_usesTheAddressOfTheLogin_evenIfTheConnectionMovedMeanwhile() {
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.typedAndSent()
        connection = ConnectionState.Online(PUBLIC, ConnectionState.Via.PUBLIC)
        gate.complete(SendResult.NOT_ACCEPTED)
        assertEquals(listOf(LOCAL to TV_REFRESH, LOCAL to CURRENT_REFRESH), login.refreshes)
        assertEquals(LOCAL, ended.single().second)
    }

    @Test fun aSenderThatThrows_endsTheSession_andFails() {
        sendResult = { throw IllegalStateException("boom") }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertEquals(1, ended.size)
    }

    @Test fun whileSending_theFieldIsEmpty_andASecondTapSendsNothing() {
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.typedAndSent()
        val step = v.step.value as TvHandoverStep.Confirm
        assertTrue(step.working)
        assertEquals("", step.password)
        v.onPassword("x")
        v.send()
        assertEquals(1, login.calls.size)
        gate.complete(SendResult.SIGNED_IN)
        assertEquals(TvHandoverStep.Done, v.step.value)
    }

    /** Leaving the page while the packet is on its way: the result still counts, and a failed one still ends the session. */
    @Test fun leavingThePageWhileSending_stillEndsTheSessionOfAFailedHandover() {
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.typedAndSent()
        v.viewModelScope.cancel()
        gate.complete(SendResult.NOT_ACCEPTED)
        assertEquals(listOf(Triple(account, LOCAL, CURRENT_REFRESH)), ended)
    }

    @Test fun leavingThePageWhileSending_endsNothing_whenTheTvSignedIn() {
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.typedAndSent()
        v.viewModelScope.cancel()
        gate.complete(SendResult.SIGNED_IN)
        assertTrue(ended.isEmpty() && login.refreshes.isEmpty())
    }

    @Test fun noAccount_atSend_fails_withoutSigningIn() {
        val v = vm()
        v.scanned()
        v.onPassword(PASSWORD)
        store.acc.value = null
        v.send()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(login.calls.isEmpty())
        assertNoSession()
    }

    /** N4: the account the question named is the one used; another active account sends nothing. */
    @Test fun anotherAccount_atSend_fails_withoutSigningIn() {
        val v = vm()
        v.scanned()
        v.onPassword(PASSWORD)
        store.acc.value = account.copy(serverId = "s2", userId = "u2", username = "matthias")
        v.send()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(login.calls.isEmpty())
        assertNoSession()
    }

    @Test fun again_startsOver() {
        sendResult = { SendResult.NOT_ACCEPTED }
        val v = vm()
        v.typedAndSent()
        v.again()
        assertEquals(TvHandoverStep.Intro(), v.step.value)
        // The old code is gone: without a new scan nothing can be sent.
        v.onPassword(PASSWORD)
        v.send()
        assertEquals(1, sent.size)
    }

    // --- Secrets ---

    @Test fun noStateNamesThePasswordTheTokenTheCodeOrTheAddresses() {
        val seen = mutableListOf<TvHandoverStep>()
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.startScan(); seen += v.step.value
        v.onScanned(code.text); seen += v.step.value
        v.onPassword(PASSWORD); seen += v.step.value
        v.send(); seen += v.step.value
        gate.complete(SendResult.NOT_ACCEPTED); seen += v.step.value
        seen += TvHandoverStep.Confirm("4821", listOf("http://192.168.1.20:13378"), "fabian", password = PASSWORD)
        for (step in seen) {
            val text = step.toString()
            for (secret in listOf(PASSWORD, TV_REFRESH, CURRENT_REFRESH, code.text, "192.168.1", "abs.example.org")) {
                assertFalse("$secret in $text", secret in text)
            }
        }
    }
}
