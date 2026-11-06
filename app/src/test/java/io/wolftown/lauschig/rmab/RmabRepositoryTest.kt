package io.wolftown.lauschig.rmab

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.external.AudibleVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class RmabRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var repo: RmabRepository
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var storeFile: File
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<String>()
    private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }
    private var now = 1_000_000L
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    /** The signed-in account. */
    private val active = kotlinx.coroutines.flow.MutableStateFlow<String?>(a)

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private val volume = AudibleVolume("B0ASIN", "Der Titel", authors = listOf("Ann Autor", "Bo"), narrators = listOf("Nick"), coverUrl = "http://img/c.jpg")

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                bodies += request.body.readUtf8()
                return handler(request)
            }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeFile = File(tmp.root, "rmab.preferences_pb")
        repo = newRepo()
    }

    private fun newRepo(): RmabRepository {
        store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        return RmabRepository(store, cipher, { "Pixel 9" }, active, nowMs = { now })
    }

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val url get() = server.url("/").toString().trimEnd('/')

    private fun loginOk(p: String) = when (p) {
        "/api/auth/local/login" -> json(200, """{"success":true,"user":{"id":"1"},"accessToken":"JWT","refreshToken":"R"}""")
        "/api/user/api-tokens" -> json(201, """{"token":{"id":"t1"},"fullToken":"rmab_abc"}""")
        else -> MockResponse().setResponseCode(404)
    }

    @Test fun connect_createsApiToken_storesOnlyToken() = runBlocking {
        handler = { loginOk(it.path!!) }
        assertEquals(RmabConnectResult.Ok, repo.connect(url, "fabi", "secret", allowCleartext = true))

        assertEquals(listOf("/api/auth/local/login", "/api/user/api-tokens"), seen.map { it.path })
        assertEquals("Bearer JWT", seen[1].getHeader("Authorization"))
        assertTrue(bodies[1].contains("\"name\":\"Lauschig (Pixel 9)\""))
        assertTrue(bodies[1].contains("\"expiresAt\":null"))
        assertEquals(RmabConfig(server.url("/").toString(), "fabi"), repo.config.first())

        // Nothing but the permanent token (encrypted) and the confirmed host is persisted: no JWT, refresh token or password.
        val raw = storeFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(raw.contains("enc:rmab_abc"))
        assertFalse(raw.contains("JWT"))
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("\"R\""))
    }

    @Test fun connect_pendingApproval() = runBlocking {
        handler = { json(200, """{"success":false,"pendingApproval":true,"message":"wait"}""") }
        assertEquals(RmabConnectResult.PendingApproval, repo.connect(url, "fabi", "pw", allowCleartext = true))
        assertNull(repo.config.first())
    }

    @Test fun connect_wrongLogin_andLocalLoginDisabled() = runBlocking {
        handler = { json(401, """{"error":"bad"}""") }
        assertEquals(RmabConnectResult.WrongLogin, repo.connect(url, "fabi", "pw", allowCleartext = true))
        handler = { json(403, """{"error":"off"}""") }
        assertEquals(RmabConnectResult.LocalLoginDisabled, repo.connect(url, "fabi", "pw", allowCleartext = true))
    }

    @Test fun connect_http_needsConfirmation_beforeAnyRequest() = runBlocking {
        val r = repo.connect(url, "fabi", "pw")
        assertTrue(r is RmabConnectResult.NeedsCleartextConfirmation)
        assertEquals(0, seen.size)
    }

    @Test fun connectWithToken_checksMe_andUsesBearer() = runBlocking {
        handler = { json(200, """{"user":{"username":"oidcuser"}}""") }
        assertEquals(RmabConnectResult.Ok, repo.connectWithToken(url, " rmab_x ", allowCleartext = true))
        assertEquals("/api/auth/me", seen.single().path)
        assertEquals("Bearer rmab_x", seen.single().getHeader("Authorization"))
        assertEquals("oidcuser", repo.config.first()!!.username)
    }

    private suspend fun connected() {
        handler = { loginOk(it.path!!) }
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        seen.clear(); bodies.clear()
    }

    @Test fun request_sendsAsinTitleAuthor() = runBlocking {
        connected()
        handler = { json(201, """{"success":true,"request":{"id":"r1"}}""") }
        assertEquals(RmabRequestResult.Created, repo.request(volume))
        val req = seen.single()
        assertEquals("/api/requests", req.path)
        assertEquals("Bearer rmab_abc", req.getHeader("Authorization"))
        val b = bodies.single()
        assertTrue(b, b.contains("\"asin\":\"B0ASIN\"") && b.contains("\"title\":\"Der Titel\"") && b.contains("\"author\":\"Ann Autor\""))
    }

    @Test fun request_withoutAuthor_sendsNothing() = runBlocking {
        connected()
        assertEquals(RmabRequestResult.NoAuthor, repo.request(volume.copy(authors = emptyList())))
        assertEquals(0, seen.size)
    }

    @Test fun request_409Duplicate_mapsAlreadyRequested() = runBlocking {
        connected()
        val cases = mapOf(
            "DuplicateRequest" to RmabRequestResult.AlreadyRequested,
            "AlreadyAvailable" to RmabRequestResult.AlreadyAvailable,
            "BeingProcessed" to RmabRequestResult.BeingProcessed,
        )
        for ((err, expected) in cases) {
            handler = { json(409, """{"error":"$err","message":"m"}""") }
            assertEquals(expected, repo.request(volume))
        }
        handler = { json(409, """{"error":"Ignored","message":"nope"}""") }
        assertEquals(RmabRequestResult.Failed("nope"), repo.request(volume))
    }

    @Test fun request_401_mapsNotConnected_keepsConfigWithHint() = runBlocking {
        connected()
        handler = { json(401, """{"error":"Unauthorized"}""") }
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
        val c = repo.config.first()!!
        assertTrue(c.needsReconnect)
        assertEquals("fabi", c.username)
        seen.clear()
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
        assertEquals(0, seen.size)
    }

    @Test fun request_notConnected() = runBlocking {
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
    }

    @Test fun status_pagesThroughCursor_mapsGroups_andCaches() = runBlocking {
        connected()
        handler = { r ->
            if (r.path!!.contains("cursor=c2")) json(200, """{"requests":[
                {"id":"3","status":"available","audiobook":{"audibleAsin":"C"}},
                {"id":"4","status":"failed","audiobook":{"audibleAsin":"A"}},
                {"id":"5","status":"mystery","audiobook":{"audibleAsin":"Z"}}],"nextCursor":null}""")
            else json(200, """{"requests":[
                {"id":"1","status":"awaiting_search","audiobook":{"audibleAsin":"A"}},
                {"id":"2","status":"downloading","audiobook":{"audibleAsin":"B"}}],"nextCursor":"c2"}""")
        }
        val m = repo.statusByAsin()
        assertEquals(mapOf("A" to RmabStatus.WAITING, "B" to RmabStatus.ACTIVE, "C" to RmabStatus.DONE), m)
        assertEquals(2, seen.size)
        assertTrue(seen[0].path!!.startsWith("/api/requests?myOnly=true&take=100"))
        assertTrue(seen[1].path!!.contains("cursor=c2"))

        repo.statusByAsin()
        assertEquals("cached within 60 s", 2, seen.size)
        now += 61_000
        repo.statusByAsin()
        assertEquals(4, seen.size)
    }

    @Test fun status_droppedAfterOwnRequest() = runBlocking {
        connected()
        handler = { json(200, """{"requests":[],"nextCursor":null}""") }
        repo.statusByAsin()
        handler = { json(201, """{"success":true}""") }
        repo.request(volume)
        handler = { json(200, """{"requests":[{"id":"1","status":"pending","audiobook":{"audibleAsin":"B0ASIN"}}]}""") }
        assertEquals(RmabStatus.WAITING, repo.statusByAsin()["B0ASIN"])
    }

    @Test fun unreachable_returnsResultNoThrow() = runBlocking {
        connected()
        server.shutdown()
        assertEquals(RmabRequestResult.Unreachable, repo.request(volume))
        assertEquals(emptyMap<String, RmabStatus>(), repo.statusByAsin())
        assertEquals(RmabConnectResult.Unreachable, repo.connect(url, "fabi", "pw", allowCleartext = true))
        assertEquals(RmabConnectResult.Unreachable, repo.connectWithToken(url, "t", allowCleartext = true))
    }

    @Test fun brokenJson_isNoCrash() = runBlocking {
        connected()
        handler = { json(200, "<html>nope") }
        assertEquals(emptyMap<String, RmabStatus>(), repo.statusByAsin())
        handler = { json(200, "<html>nope") }
        assertEquals(RmabConnectResult.Unreachable, repo.connect(url, "fabi", "pw", allowCleartext = true))
    }

    @Test fun disconnect_clearsConfig() = runBlocking {
        connected()
        repo.disconnect()
        assertNull(repo.config.first())
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
    }

    @Test fun connect_tokenCreationRefused_isNotUnreachable() = runBlocking {
        handler = { if (it.path == "/api/auth/local/login") loginOk(it.path!!) else json(403, """{"error":"Forbidden"}""") }
        assertEquals(RmabConnectResult.TokenCreationFailed, repo.connect(url, "fabi", "pw", allowCleartext = true))
        assertNull(repo.config.first())
    }

    /** A stored http:// config whose host was never confirmed (or was edited) must not be spoken to. */
    private suspend fun storeUnconfirmedHttpConfig() {
        store.edit {
            it[stringPreferencesKey("url:$a")] = server.url("/").toString()
            it[stringPreferencesKey("username:$a")] = "fabi"
            it[stringPreferencesKey("token:$a")] = "enc:rmab_abc"
        }
    }

    @Test fun storedHttpConfig_withoutConfirmedCleartext_refusesRequestAndStatus() = runBlocking {
        storeUnconfirmedHttpConfig()
        handler = { json(201, """{"success":true}""") }
        assertEquals(RmabRequestResult.Unreachable, repo.request(volume))
        assertEquals(emptyMap<String, RmabStatus>(), repo.statusByAsin())
        assertEquals(0, server.requestCount)
    }

    @Test fun storedHttpConfig_confirmedForOtherHost_refuses() = runBlocking {
        storeUnconfirmedHttpConfig()
        store.edit { it[stringPreferencesKey("allow_cleartext:$a")] = "http://other.example:80" }
        assertEquals(RmabRequestResult.Unreachable, repo.request(volume))
        assertEquals(0, server.requestCount)
    }

    @Test fun redirectToOtherHost_isNotFollowed_andGetsNoBearer() = runBlocking {
        connected()
        val other = MockWebServer().also { it.start() }
        try {
            handler = { MockResponse().setResponseCode(302).setHeader("Location", other.url("/api/requests").toString()) }
            assertEquals(RmabRequestResult.Unreachable, repo.request(volume))
            // The cleartext guard stops the hop; either way the foreign host never sees the token.
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    private val viewModels = io.wolftown.lauschig.TestViewModels()

    @Test fun viewModel_keepsOptimisticWaiting_untilServerReportsAsin() = runBlocking {
        connected()
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            handler = { r ->
                if (r.method == "POST") json(201, """{"success":true}""") else json(200, """{"requests":[{"id":"9","status":"downloading","audiobook":{"audibleAsin":"OTHER"}}]}""")
            }
            val vm = viewModels.own(RmabViewModel(repo))
            val job = scope.launch { vm.state.collect { } }
            withTimeout(5_000) { while (!vm.state.value.connected) delay(20) }
            vm.request(volume)
            withTimeout(5_000) { while (vm.state.value.outcomes[volume.asin] == null) delay(20) }
            delay(300)
            assertEquals(RmabStatus.WAITING, vm.state.value.statuses[volume.asin])
            assertEquals(RmabStatus.ACTIVE, vm.state.value.statuses["OTHER"])
            assertEquals(false, vm.state.value.outcomes[volume.asin]!!.isError)
            job.cancel()
        } finally {
            viewModels.clear()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    // Per account (Phase 7, Task 1.1): the connection of a profile waits for it, with its token.

    private fun rmabLogin(token: String): (RecordedRequest) -> MockResponse = { r ->
        when (r.path) {
            "/api/auth/local/login" -> json(200, """{"success":true,"user":{"id":"1"},"accessToken":"JWT","refreshToken":"R"}""")
            "/api/user/api-tokens" -> json(201, """{"token":{"id":"t1"},"fullToken":"$token"}""")
            else -> json(201, """{"success":true,"request":{"id":"r1"}}""")
        }
    }

    @Test fun eachAccount_hasItsOwnConnection_andItWaitsForItsReturn() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        seen.clear()

        active.value = b
        assertNull("B sees nothing of A's connection", repo.config.first())
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
        assertEquals(emptyMap<String, RmabStatus>(), repo.statusByAsin())
        assertEquals(0, seen.size)

        handler = rmabLogin("rmab_b")
        repo.connect(url, "matthias", "pw", allowCleartext = true)
        assertEquals("matthias", repo.config.first()!!.username)
        seen.clear()
        repo.request(volume)
        assertEquals("Bearer rmab_b", seen.single().getHeader("Authorization"))

        active.value = a
        assertEquals("fabi", repo.config.first()!!.username)
        seen.clear()
        repo.request(volume)
        assertEquals("Bearer rmab_a", seen.single().getHeader("Authorization"))
    }

    @Test fun theStatusCache_isOneAccounts() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        handler = { json(200, """{"requests":[{"id":"1","status":"available","audiobook":{"audibleAsin":"A"}}],"nextCursor":null}""") }
        assertEquals(mapOf("A" to RmabStatus.DONE), repo.statusByAsin())
        active.value = b
        handler = rmabLogin("rmab_b")
        repo.connect(url, "matthias", "pw", allowCleartext = true)
        handler = { json(200, """{"requests":[],"nextCursor":null}""") }
        assertEquals("not A's cached answer", emptyMap<String, RmabStatus>(), repo.statusByAsin())
    }

    @Test fun aRejectedToken_marksOnlyItsAccount() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        active.value = b
        handler = rmabLogin("rmab_b")
        repo.connect(url, "matthias", "pw", allowCleartext = true)
        handler = { json(401, """{"error":"Unauthorized"}""") }
        repo.request(volume)
        assertTrue(repo.config.first()!!.needsReconnect)
        active.value = a
        assertFalse(repo.config.first()!!.needsReconnect)
    }

    @Test fun aSignInThatLandsAfterTheSwitch_storesForTheAccountThatStartedIt() = runBlocking {
        handler = { r ->
            if (r.path == "/api/user/api-tokens") active.value = b // the switch comes while the connect runs
            rmabLogin("rmab_a")(r)
        }
        assertEquals(RmabConnectResult.Ok, repo.connect(url, "fabi", "pw", allowCleartext = true))
        assertNull("not B's", repo.config.first())
        active.value = a
        assertEquals("fabi", repo.config.first()!!.username)
    }

    @Test fun forget_goesOnlyForThatAccount_disconnect_isTheActiveOne() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        active.value = b
        handler = rmabLogin("rmab_b")
        repo.connect(url, "matthias", "pw", allowCleartext = true)
        repo.forget(a)
        assertEquals("matthias", repo.config.first()!!.username)
        active.value = a
        assertNull(repo.config.first())
        active.value = b
        repo.disconnect()
        assertNull(repo.config.first())
        val raw = storeFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("no token stays behind", raw.contains("rmab_"))
    }

    @Test fun signedOut_thereIsNoConnection() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        active.value = null
        assertNull(repo.config.first())
        assertEquals(RmabRequestResult.NotConnected, repo.request(volume))
    }

    /** What 0.4.0 stored: one connection for the device. */
    private suspend fun oldVersionStored() = store.edit {
        it[stringPreferencesKey("url")] = server.url("/").toString()
        it[stringPreferencesKey("username")] = "fabi"
        it[stringPreferencesKey("token")] = "enc:rmab_old"
        it[stringPreferencesKey("allow_cleartext")] = server.url("/").let { u -> "${u.scheme}://${u.host}:${u.port}" }
        it[androidx.datastore.preferences.core.booleanPreferencesKey("needs_reconnect")] = true
    }

    @Test fun upgrade_theOldConnectionIsTheActiveAccounts() = runBlocking {
        oldVersionStored()
        repo.migrate(a)
        assertEquals(RmabConfig(server.url("/").toString(), "fabi", needsReconnect = true), repo.config.first())
        store.edit { it.remove(androidx.datastore.preferences.core.booleanPreferencesKey("needs_reconnect:$a")) }
        handler = { json(201, """{"success":true,"request":{"id":"r1"}}""") }
        assertEquals("token and confirmed cleartext moved with it", RmabRequestResult.Created, repo.request(volume))
        assertEquals("Bearer rmab_old", seen.single().getHeader("Authorization"))
        active.value = b
        assertNull(repo.config.first())
    }

    @Test fun upgrade_signedOut_theOldConnectionHasNoOwner_andGoes() = runBlocking {
        oldVersionStored()
        repo.migrate(null)
        repo.migrate(a)
        assertNull(repo.config.first())
        val raw = storeFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("rmab_old"))
    }

    @Test fun upgrade_runsOnce_andKeepsAnAccountsOwnConnection() = runBlocking {
        handler = rmabLogin("rmab_a")
        repo.connect(url, "fabi", "pw", allowCleartext = true)
        oldVersionStored()
        repo.migrate(a)
        repo.migrate(b)
        assertEquals("fabi", repo.config.first()!!.username)
        assertFalse(repo.config.first()!!.needsReconnect)
        active.value = b
        assertNull(repo.config.first())
    }

    /** For the masker: the connection of an account that is set aside is still this device's. */
    @Test fun allConfigs_everyAccounts_notOnlyTheActiveOnes() = runBlocking {
        store.edit {
            it[stringPreferencesKey("url:$a")] = "https://rmab.a.example/"
            it[stringPreferencesKey("username:$a")] = "fabi"
            it[stringPreferencesKey("url:$b")] = "http://rmab.lan:3030/"
            it[stringPreferencesKey("username:$b")] = "kind"
        }
        active.value = b
        assertEquals(setOf("fabi", "kind"), repo.allConfigs.first().map { it.username }.toSet())
        assertEquals(setOf("https://rmab.a.example/", "http://rmab.lan:3030/"), repo.allConfigs.first().map { it.url }.toSet())
        assertEquals("the active one's stays what it was", "kind", repo.config.first()!!.username)
        active.value = null
        assertEquals(2, repo.allConfigs.first().size)
    }

    @Test fun allConfigs_noneAtAll() = runBlocking {
        assertEquals(emptyList<RmabConfig>(), repo.allConfigs.first())
    }
}
