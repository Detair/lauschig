package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ServerAddresses
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CleartextGuardTest {
    private lateinit var server: MockWebServer
    private val store = FakeAccountStore()

    @Before fun setUp() { server = MockWebServer().apply { enqueue(MockResponse().setBody("ok")); start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = OkHttpClient.Builder().addInterceptor(CleartextGuardInterceptor(store)).build()
    private fun account(allow: Set<String>) = Account(
        "aaaaaaaaaaaaaaaa", "u", "n", false, ServerAddresses(server.url("/"), null), allow,
    )

    @Test
    fun cleartextToUnconfirmedHost_isRefused() {
        store.acc.value = account(emptySet())
        val e = runCatching { client().newCall(Request.Builder().url(server.url("/ping")).build()).execute() }
            .exceptionOrNull()
        assertTrue("expected IOException, got $e", e is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleartextWithoutAccount_isRefused() {
        val e = runCatching { client().newCall(Request.Builder().url(server.url("/ping")).build()).execute() }
            .exceptionOrNull()
        assertTrue(e is IOException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleartextToConfirmedHost_passes() {
        store.acc.value = account(setOf(server.url("/").cleartextKey()))
        client().newCall(Request.Builder().url(server.url("/ping")).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(1, server.requestCount)
    }
}
