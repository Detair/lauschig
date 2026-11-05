package io.wolftown.lauschig.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.wolftown.lauschig.account.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OidcLoginTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private var now = 1_000_000L

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private val loginJson = """{"user":{"id":"u1","username":"fabian","type":"user","accessToken":"acc","refreshToken":"ref"},"userDefaultLibraryId":"l1"}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "oidc_pending.preferences_pb") }
    }

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun oidc() = OidcLogin(store, cipher, OkHttpClient(), nowMs = { now })
    private val base get() = server.url("/")

    private fun enqueueStart() = server.enqueue(
        MockResponse().setResponseCode(302).setHeader("Location", "https://idp.example/auth?x=1")
            .addHeader("Set-Cookie", "connect.sid=sess123; Path=/; HttpOnly")
            .addHeader("Set-Cookie", "auth_method=openid-mobile; Path=/"),
    )

    @Test fun start_returnsLocation_andKeepsCookies() = runBlocking {
        enqueueStart()
        val r = oidc().start(base)
        assertEquals(OidcStart.Launch("https://idp.example/auth?x=1"), r)

        val req = server.takeRequest()
        assertEquals("/auth/openid", req.requestUrl!!.encodedPath)
        val q = req.requestUrl!!
        assertEquals("code", q.queryParameter("response_type"))
        assertEquals("Lauschig", q.queryParameter("client_id"))
        assertEquals("lauschig://oauth", q.queryParameter("redirect_uri"))
        assertEquals("S256", q.queryParameter("code_challenge_method"))
        assertNotNull(q.queryParameter("state"))
        val prefs = store.data.first()
        val saved = prefs.asMap().entries.single { it.key.name == "cookies" }.value as String
        assertTrue(saved.startsWith("enc:"))
        assertTrue(saved.contains("connect.sid=sess123") && saved.contains("auth_method=openid-mobile"))
        val verifier = (prefs.asMap().entries.single { it.key.name == "verifier" }.value as String).removePrefix("enc:")
        assertEquals(64, verifier.length)
        assertEquals(OidcLogin.challenge(verifier), q.queryParameter("code_challenge"))
    }

    @Test fun start_serverUnderSubPath_keepsThePath() = runBlocking {
        enqueueStart()
        oidc().start(server.url("/audiobookshelf"))
        assertEquals("/audiobookshelf/auth/openid", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun start_400_isRedirectNotAllowed_and_networkError_isUnreachable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Invalid redirect_uri"))
        assertEquals(OidcStart.RedirectNotAllowed, oidc().start(base))
        server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(OidcStart.Unreachable, oidc().start(base))
        val dead = base
        server.shutdown()
        assertEquals(OidcStart.Unreachable, oidc().start(dead))
        Unit
    }

    private suspend fun startAndState(o: OidcLogin): String {
        enqueueStart()
        o.start(base)
        return server.takeRequest().requestUrl!!.queryParameter("state")!!
    }

    @Test fun finish_wrongState_rejected() = runBlocking {
        val o = oidc()
        val state = startAndState(o)
        try {
            o.finish(OidcCallback("code", "forged", null))
            fail("expected rejection")
        } catch (e: OidcException) {
            assertEquals(OidcException.Reason.STATE_MISMATCH, e.reason)
        }
        assertEquals("no request to the server", 1, server.requestCount)
        // the real callback still works afterwards
        server.enqueue(MockResponse().setBody(loginJson))
        assertEquals("acc", o.finish(OidcCallback("code", state, null)).response.user.accessToken)
    }

    @Test fun finish_sendsVerifierAndSessionCookie() = runBlocking {
        val o = oidc()
        val state = startAndState(o)
        val verifier = (store.data.first().asMap().entries.single { it.key.name == "verifier" }.value as String).removePrefix("enc:")
        server.enqueue(MockResponse().setBody(loginJson))
        val res = o.finish(OidcCallback("the-code", state, null))
        assertEquals("refresh", "ref", res.response.user.refreshToken)
        assertEquals(base, res.baseUrl)

        val req = server.takeRequest()
        assertEquals("/auth/openid/callback", req.requestUrl!!.encodedPath)
        assertEquals(state, req.requestUrl!!.queryParameter("state"))
        assertEquals("the-code", req.requestUrl!!.queryParameter("code"))
        assertEquals(verifier, req.requestUrl!!.queryParameter("code_verifier"))
        val cookie = req.getHeader("Cookie")!!
        assertTrue(cookie, cookie.contains("connect.sid=sess123") && cookie.contains("auth_method=openid-mobile"))
        assertTrue("pending cleared after success", store.data.first().asMap().isEmpty())
    }

    @Test fun finish_serverRejects_clearsPending_andThrows() = runBlocking {
        val o = oidc()
        val state = startAndState(o)
        server.enqueue(MockResponse().setResponseCode(400).setBody("No session"))
        try {
            o.finish(OidcCallback("c", state, null))
            fail()
        } catch (e: OidcException) {
            assertEquals(OidcException.Reason.REJECTED, e.reason)
        }
        assertTrue(store.data.first().asMap().isEmpty())
    }

    @Test fun finish_idpError_clearsPending() = runBlocking {
        val o = oidc()
        val state = startAndState(o)
        try { o.finish(OidcCallback(null, state, "access_denied")); fail() } catch (e: OidcException) {
            assertEquals(OidcException.Reason.REJECTED, e.reason)
        }
        assertTrue(store.data.first().asMap().isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun matchesPending_onlyForOurState() = runBlocking {
        val o = oidc()
        assertFalse(o.matchesPending(OidcCallback("c", "x", null)))
        val state = startAndState(o)
        assertTrue(o.matchesPending(OidcCallback("c", state, null)))
        assertFalse(o.matchesPending(OidcCallback("c", "forged", null)))
        assertFalse(o.matchesPending(OidcCallback("c", null, null)))
    }

    @Test fun start_locationNotHttp_isUnreachable_andRelativeIsResolved() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "javascript:alert(1)"))
        assertEquals(OidcStart.Unreachable, oidc().start(base))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/realms/x/auth"))
        assertEquals(OidcStart.Launch(base.resolve("/realms/x/auth").toString()), oidc().start(base))
    }

    @Test fun flowContext_theAddMode_survivesInPending() = runBlocking {
        enqueueStart()
        oidc().start(base, OidcFlowContext(null, "https://abs.example.org/", addProfile = true))
        val state = server.takeRequest().requestUrl!!.queryParameter("state")!!
        server.enqueue(MockResponse().setBody(loginJson))
        assertEquals(true, oidc().finish(OidcCallback("c", state, null)).flow.addProfile)
    }

    @Test fun flowContext_survivesInPending() = runBlocking {
        enqueueStart()
        val flow = OidcFlowContext("http://nas.lan:13378/", "https://abs.example.org/", true, "HomeNet", setOf("Old"))
        oidc().start(base, flow)
        val state = server.takeRequest().requestUrl!!.queryParameter("state")!!
        server.enqueue(MockResponse().setBody(loginJson))
        assertEquals(flow, oidc().finish(OidcCallback("c", state, null)).flow)
    }

    @Test fun finish_withoutPendingOrAfterTtl_isRejected() = runBlocking {
        try { oidc().finish(OidcCallback("c", "s", null)); fail() } catch (e: OidcException) { assertEquals(OidcException.Reason.NO_PENDING, e.reason) }
        val o = oidc()
        val state = startAndState(o)
        now += OidcLogin.TTL_MS + 1
        try { o.finish(OidcCallback("c", state, null)); fail() } catch (e: OidcException) { assertEquals(OidcException.Reason.NO_PENDING, e.reason) }
    }

    @Test fun pending_survivesRecreate() = runBlocking {
        val state = startAndState(oidc())
        // New instance, new DataStore on the same file = process restart while the Custom Tab was open.
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "oidc_pending.preferences_pb") }
        server.enqueue(MockResponse().setBody(loginJson))
        val res = oidc().finish(OidcCallback("c", state, null))
        assertEquals("acc", res.response.user.accessToken)
        assertTrue(server.takeRequest().getHeader("Cookie")!!.contains("connect.sid=sess123"))
    }
}
