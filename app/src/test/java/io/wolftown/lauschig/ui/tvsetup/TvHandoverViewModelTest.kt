package io.wolftown.lauschig.ui.tvsetup

import androidx.lifecycle.viewModelScope
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.absapi.auth.StatusResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.cleartextKey
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
    override suspend fun refresh(baseUrl: HttpUrl, refreshToken: String): LoginResponse = error("the phone never exchanges a token here")
}

private const val USER_ID = "u1"
private const val TV_REFRESH = "tv-refresh-token"
private const val PASSWORD = "geheim-123"

private fun httpError(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

@OptIn(ExperimentalCoroutinesApi::class)
class TvHandoverViewModelTest {
    private val store = FakeAccountStore()
    private val login = FakeLogin()
    private val sent = mutableListOf<Pair<TvSetupCode, PhoneHandover>>()
    private var sendResult: suspend () -> SendResult = { SendResult.SIGNED_IN }
    private val ended = mutableListOf<Pair<Account, String>>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = TestViewModels()

    private val code = TvSetupCode("192.168.1.50", 41234, ByteArray(32) { it.toByte() })

    private val account = Account(
        serverId = "s1", userId = USER_ID, username = "fabian", isAdmin = false,
        addresses = ServerAddresses("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/abs/".toHttpUrl()),
        allowCleartext = setOf("http://192.168.1.20:13378/".toHttpUrl().cleartextKey()),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store.acc.value = account
    }

    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(
        TvHandoverViewModel(store, login, send = { c, h -> sent += c to h; sendResult() }, endSession = { a, r -> ended += a to r }),
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

    @Test fun nothingIsSent_withoutAPassword() {
        val v = vm()
        v.scanned()
        v.send()
        assertTrue(login.calls.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun success_signsInAtTheLocalAddressFirst_sendsTheAccountsAddressesAndTheNewToken_andEndsNothing() {
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf("http://192.168.1.20:13378/".toHttpUrl()), login.calls.map { it.first })
        assertEquals(LoginRequest("fabian", PASSWORD), login.calls.single().second)
        val (to, handover) = sent.single()
        assertEquals(code, to)
        assertEquals(PhoneHandover(local = "http://192.168.1.20:13378", public = "https://abs.example.org/abs", refresh = TV_REFRESH), handover)
        assertEquals(TvHandoverStep.Done, v.step.value)
        assertTrue("no session is ended after the TV signed in", ended.isEmpty())
    }

    @Test fun anUnreachableLocalAddress_fallsThroughToThePublicOne() {
        login.behaviour = { url ->
            if (url.isHttps) LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", TV_REFRESH)) else throw IOException("no route")
        }
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf("http://192.168.1.20:13378/".toHttpUrl(), "https://abs.example.org/abs/".toHttpUrl()), login.calls.map { it.first })
        assertEquals(TvHandoverStep.Done, v.step.value)
    }

    @Test fun anUnconfirmedHttpAddress_neverGetsThePassword() {
        store.acc.value = account.copy(allowCleartext = emptySet())
        val v = vm()
        v.typedAndSent()
        assertEquals(listOf("https://abs.example.org/abs/".toHttpUrl()), login.calls.map { it.first })
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
            assertEquals("a wrong password is final, the next address is not asked", 1, login.calls.size)
            assertTrue(sent.isEmpty())
            assertTrue(ended.isEmpty())
            // Typing again clears the error and may send again: the code is still the one scanned.
            v.onPassword("richtig")
            assertEquals(null, (v.step.value as TvHandoverStep.Confirm).error)
        }
    }

    @Test fun unreachableServer_sendsNothing() {
        login.behaviour = { throw IOException("offline") }
        val v = vm()
        v.typedAndSent()
        val step = v.step.value as TvHandoverStep.Confirm
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, step.error)
        assertEquals("", step.password)
        assertEquals(2, login.calls.size)
        assertTrue(sent.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test fun aServerError_triesTheNextAddress_andCountsAsUnreachable() {
        login.behaviour = { throw httpError(500) }
        val v = vm()
        v.typedAndSent()
        assertEquals(2, login.calls.size)
        assertEquals(TvHandoverError.SERVER_UNREACHABLE, (v.step.value as TvHandoverStep.Confirm).error)
    }

    @Test fun anotherUserInTheAnswer_endsThatSession_andSendsNothing() {
        login.behaviour = { LoginResponse(AbsUser("someone-else", "fabian", "user", "a", "foreign-refresh")) }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(sent.isEmpty())
        assertEquals(listOf(account to "foreign-refresh"), ended)
    }

    @Test fun anAnswerWithoutRefreshToken_sendsNothing_andHasNothingToEnd() {
        login.behaviour = { LoginResponse(AbsUser(USER_ID, "fabian", "user", "a", null)) }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(sent.isEmpty())
        assertTrue(ended.isEmpty())
    }

    @Test fun everyOtherReply_endsTheNewSessionExactlyOnce_withThatToken_andSaysWhy() {
        val expected = mapOf(
            SendResult.NOT_ACCEPTED to TvHandoverError.CODE_EXPIRED,
            SendResult.SERVER_UNREACHABLE to TvHandoverError.TV_CANNOT_REACH_SERVER,
            SendResult.SIGN_IN_REFUSED to TvHandoverError.TV_REFUSED,
            SendResult.NO_HOME_NETWORK to TvHandoverError.NO_HOME_NETWORK,
            SendResult.NOT_SENT to TvHandoverError.TV_UNREACHABLE,
            SendResult.NO_REPLY to TvHandoverError.NO_REPLY,
            SendResult.FAILED to TvHandoverError.FAILED,
            // TODO(tv-setup phone side): DECLINED gets its own error; FAILED until the phone page has a text for it.
            SendResult.DECLINED to TvHandoverError.FAILED,
        )
        assertEquals("every result but SIGNED_IN is covered", SendResult.entries.toSet() - SendResult.SIGNED_IN, expected.keys)
        for ((result, error) in expected) {
            ended.clear()
            sent.clear()
            sendResult = { result }
            val v = vm()
            v.typedAndSent()
            assertEquals(result.name, TvHandoverStep.Failed(error), v.step.value)
            assertEquals(result.name, listOf(account to TV_REFRESH), ended)
            assertEquals(1, sent.size)
        }
    }

    @Test fun aSenderThatThrows_endsTheSession_andFails() {
        sendResult = { throw IllegalStateException("boom") }
        val v = vm()
        v.typedAndSent()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertEquals(listOf(account to TV_REFRESH), ended)
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
        assertEquals(listOf(account to TV_REFRESH), ended)
    }

    @Test fun leavingThePageWhileSending_endsNothing_whenTheTvSignedIn() {
        val gate = CompletableDeferred<SendResult>()
        sendResult = { gate.await() }
        val v = vm()
        v.typedAndSent()
        v.viewModelScope.cancel()
        gate.complete(SendResult.SIGNED_IN)
        assertTrue(ended.isEmpty())
    }

    @Test fun noAccount_atSend_fails_withoutSigningIn() {
        val v = vm()
        v.scanned()
        v.onPassword(PASSWORD)
        store.acc.value = null
        v.send()
        assertEquals(TvHandoverStep.Failed(TvHandoverError.FAILED), v.step.value)
        assertTrue(login.calls.isEmpty())
        assertTrue(sent.isEmpty())
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
            for (secret in listOf(PASSWORD, TV_REFRESH, code.text, "192.168.1", "abs.example.org")) {
                assertFalse("$secret in $text", secret in text)
            }
        }
    }
}
