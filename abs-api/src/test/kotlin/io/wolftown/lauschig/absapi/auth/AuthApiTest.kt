package io.wolftown.lauschig.absapi.auth

import io.wolftown.lauschig.absapi.AbsClientFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = AbsClientFactory.retrofit(server.url("/"), OkHttpClient()).create(AuthApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    @Test
    fun login_sendsReturnTokensHeader_andParsesTokens() = runBlocking {
        server.enqueue(json(fixture("login.json")))

        val res = api.login(LoginRequest("alice", "secret"))

        val req = server.takeRequest()
        assertEquals("/login", req.path)
        assertEquals("POST", req.method)
        assertEquals("true", req.getHeader("x-return-tokens"))
        assertTrue(req.body.readUtf8().contains("\"username\":\"alice\""))
        assertNotNull(res.user.accessToken)
        assertNotNull(res.user.refreshToken)
        assertEquals("lib_00000000-0000-4000-8000-0000000000d1", res.userDefaultLibraryId)
        assertEquals("2.37.1", res.serverSettings?.version)
        assertEquals(1, res.user.mediaProgress.size)
        assertEquals(9000.125, res.user.mediaProgress[0].currentTime, 0.0)
        assertEquals(1761900000000L, res.user.mediaProgress[0].lastUpdate)
    }

    @Test
    fun refresh_sendsRefreshHeader() = runBlocking {
        server.enqueue(json(fixture("refresh.json")))

        val res = api.refresh("r1")

        val req = server.takeRequest()
        assertEquals("/auth/refresh", req.path)
        assertEquals("POST", req.method)
        assertEquals("r1", req.getHeader("x-refresh-token"))
        assertEquals("ACCESS_TOKEN_REDACTED_2", res.user.accessToken)
        assertEquals("REFRESH_TOKEN_REDACTED_2", res.user.refreshToken)
    }

    @Test
    fun logout_sendsTheRefreshTokenAsHeader_toTheServerRoot() = runBlocking {
        // ABS 2.37.1 Auth.js: POST /logout (no /api prefix, no auth), token from the cookie or x-refresh-token.
        server.enqueue(json("""{"redirect_url":null}"""))

        val res = api.logout("r1")

        val req = server.takeRequest()
        assertEquals("/logout", req.path)
        assertEquals("POST", req.method)
        assertEquals("r1", req.getHeader("x-refresh-token"))
        assertEquals(null, req.getHeader("Authorization"))
        assertEquals(null, res.redirectUrl)
    }

    @Test
    fun logout_underASubPath_staysUnderIt() = runBlocking {
        server.enqueue(json("""{"redirect_url":"https://idp.example/logout"}"""))
        val sub = AbsClientFactory.retrofit(server.url("/abs"), OkHttpClient()).create(AuthApi::class.java)

        val res = sub.logout("r1")

        assertEquals("/abs/logout", server.takeRequest().path)
        assertEquals("https://idp.example/logout", res.redirectUrl)
    }

    @Test
    fun unknownFields_areIgnored() = runBlocking {
        server.enqueue(json(fixture("login.json").replaceFirst("{", "{\"brandNewTopLevel\": {\"x\": 1},")))

        val res = api.login(LoginRequest("alice", "secret"))

        assertEquals("alice", res.user.username)
    }

    @Test
    fun ping_parsesSuccess() = runBlocking {
        server.enqueue(json("""{"success":true}"""))

        assertTrue(api.ping().success)
        assertEquals("/ping", server.takeRequest().path)
    }

    @Test
    fun retrofit_withSubpathBaseUrl_withoutTrailingSlash_resolvesUnderSubpath() = runBlocking {
        server.enqueue(json("""{"success":true}"""))
        val sub = AbsClientFactory.retrofit(server.url("/abs"), OkHttpClient()).create(AuthApi::class.java)

        sub.ping()

        assertEquals("/abs/ping", server.takeRequest().path)
    }

    @Test
    fun login_withBadProgressRows_stillParses() = runBlocking {
        val rows = """[{"currentTime":1.0,"duration":2.0,"progress":0.5,"isFinished":false,"lastUpdate":6},{"libraryItemId":"x","currentTime":null}]"""
        val body = fixture("login.json").replace(Regex("\"mediaProgress\": \\[.*?\\n    \\],", RegexOption.DOT_MATCHES_ALL), "\"mediaProgress\": $rows,")
        server.enqueue(json(body))

        val res = api.login(LoginRequest("alice", "secret"))

        assertEquals(1, res.user.mediaProgress.size)
        assertNotNull(res.user.accessToken)
        assertEquals(null, res.user.mediaProgress[0].libraryItemId)
    }

    @Test
    fun status_parsesAuthMethods_andFormData() = runBlocking {
        server.enqueue(json(fixture("status.json")))

        val st = api.status()

        assertEquals("/status", server.takeRequest().path)
        assertEquals("2.37.1", st.serverVersion)
        assertEquals(listOf("local", "openid"), st.authMethods)
        assertEquals("Login with Authentik", st.authFormData!!.authOpenIDButtonText)
        assertEquals(false, st.authFormData!!.authOpenIDAutoLaunch)
        assertEquals(null, st.authFormData!!.authLoginCustomMessage)
    }

    @Test
    fun status_minimalBody_usesDefaults() = runBlocking {
        server.enqueue(json("""{"isInit":true}"""))

        val st = api.status()

        assertEquals(null, st.serverVersion)
        assertTrue(st.authMethods.isEmpty())
        assertEquals(null, st.authFormData)
    }
}
