package io.wolftown.lauschig.rmab

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.external.AudibleVolume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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

/**
 * The build switch `lauschig.rmab` (see app/build.gradle.kts), here as the repository's `enabled`. Off: nothing of
 * ReadMeABook reaches the network, even with a connection an earlier build stored, and what was stored goes.
 */
class RmabSwitchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val active = MutableStateFlow<String?>(a)
    private val viewModels = TestViewModels()

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }

    private val volume = AudibleVolume("B0ASIN", "Der Titel", authors = listOf("Ann Autor"))

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        // A ReadMeABook that answers everything: whatever reaches it would succeed.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/local/login" -> json(200, """{"success":true,"user":{"id":"1"},"accessToken":"JWT","refreshToken":"R"}""")
                "/api/user/api-tokens" -> json(201, """{"token":{"id":"t1"},"fullToken":"rmab_abc"}""")
                "/api/auth/me" -> json(200, """{"user":{"id":"1","username":"fabi"}}""")
                else -> if (request.method == "POST") json(201, """{"success":true,"request":{"id":"r1"}}""")
                else json(200, """{"requests":[{"id":"9","status":"downloading","audiobook":{"audibleAsin":"B0ASIN"}}]}""")
            }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "rmab.preferences_pb") }
    }

    @After fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val url get() = server.url("/").toString().trimEnd('/')

    private fun repo(enabled: Boolean) = RmabRepository(store, cipher, { "Pixel 9" }, active, enabled = enabled)

    /** What a build with the feature left on the device: a usable connection of the signed-in account. Returns the requests so far. */
    private suspend fun storedByABuildWithTheFeature(): Int {
        assertEquals(RmabConnectResult.Ok, repo(enabled = true).connect(url, "fabi", "pw", allowCleartext = true))
        assertTrue(store.data.first().asMap().isNotEmpty())
        return server.requestCount
    }

    @Test fun off_withAStoredConnection_theServerSeesNoRequest() = runBlocking {
        val before = storedByABuildWithTheFeature()
        val off = repo(enabled = false)
        assertEquals(emptyMap<String, RmabStatus>(), off.statusByAsin())
        assertEquals(RmabRequestResult.NotConnected, off.request(volume))
        assertEquals(RmabConnectResult.Unreachable, off.connect(url, "fabi", "pw", allowCleartext = true))
        assertEquals(RmabConnectResult.Unreachable, off.connectWithToken(url, "rmab_abc", allowCleartext = true))
        assertEquals(before, server.requestCount)
    }

    @Test fun off_theScreensViewModels_sendNothingEither() = runBlocking {
        val before = storedByABuildWithTheFeature()
        val off = repo(enabled = false)
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val browse = viewModels.own(RmabViewModel(off))
            val settings = viewModels.own(RmabSettingsViewModel(off))
            val jobs = listOf(scope.launch { browse.state.collect { } }, scope.launch { settings.state.collect { } })
            browse.request(volume)
            settings.connect(url, "fabi", "pw", tokenMode = false)
            settings.connect(url, "", "rmab_abc", tokenMode = true)
            withTimeout(5_000) { while (browse.state.value.outcomes[volume.asin] == null || settings.state.value.loading) delay(20) }
            delay(300)
            assertFalse(browse.state.value.connected)
            assertEquals(emptyMap<String, RmabStatus>(), browse.state.value.statuses)
            assertNull(settings.state.value.config)
            assertEquals(before, server.requestCount)
            jobs.forEach { it.cancel() }
        } finally {
            viewModels.clear()
            Dispatchers.resetMain()
        }
    }

    /** The control for the two tests above: the same stored connection does reach the server with the switch on. */
    @Test fun on_theStoredConnection_reachesTheServer() = runBlocking {
        val before = storedByABuildWithTheFeature()
        val on = repo(enabled = true)
        assertEquals(mapOf("B0ASIN" to RmabStatus.ACTIVE), on.statusByAsin())
        assertEquals(RmabRequestResult.Created, on.request(volume))
        assertEquals(before + 2, server.requestCount)
    }

    @Test fun off_thereIsNoConnection_forAnyScreen() = runBlocking {
        storedByABuildWithTheFeature()
        assertEquals("fabi", repo(enabled = true).config.first()!!.username)
        assertNull(repo(enabled = false).config.first())
    }

    @Test fun off_theStart_removesTheConnectionOfEveryProfile() = runBlocking {
        storedByABuildWithTheFeature()
        store.edit {
            // A saved profile's, and the one of the device from before profiles.
            it[stringPreferencesKey("url:$b")] = "https://rmab.example/"
            it[stringPreferencesKey("username:$b")] = "kind"
            it[stringPreferencesKey("token:$b")] = "enc:rmab_kind"
            it[stringPreferencesKey("url")] = "https://old.example/"
            it[stringPreferencesKey("token")] = "enc:rmab_old"
        }
        val off = repo(enabled = false)
        // Until then the diagnostic log still knows whose hosts and names it must never show.
        assertEquals(setOf("fabi", "kind"), off.allConfigs.first().map { it.username }.toSet())
        off.migrate(a)
        assertEquals(emptySet<Preferences.Key<*>>(), store.data.first().asMap().keys)
        assertEquals(emptyList<RmabConfig>(), off.allConfigs.first())
    }

    @Test fun off_theStart_signedOut_removesThemToo() = runBlocking {
        storedByABuildWithTheFeature()
        active.value = null
        repo(enabled = false).migrate(null)
        assertEquals(emptySet<Preferences.Key<*>>(), store.data.first().asMap().keys)
    }

    @Test fun off_nothingNewIsStored() = runBlocking {
        val off = repo(enabled = false)
        off.connect(url, "fabi", "pw", allowCleartext = true)
        off.connectWithToken(url, "rmab_abc", allowCleartext = true)
        assertEquals(emptySet<Preferences.Key<*>>(), store.data.first().asMap().keys)
        assertEquals(0, server.requestCount)
    }

    @Test fun on_theStart_keepsTheConnections() = runBlocking {
        storedByABuildWithTheFeature()
        val on = repo(enabled = true)
        on.migrate(a)
        assertEquals("fabi", on.config.first()!!.username)
    }
}
