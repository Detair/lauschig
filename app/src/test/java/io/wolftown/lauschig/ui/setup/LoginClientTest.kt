package io.wolftown.lauschig.ui.setup

import io.wolftown.lauschig.absapi.auth.LoginRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class LoginClientTest {
    @Test
    fun loginRedirect_isNotFollowed_credentialsNotResent() {
        val b = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val a = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(307).addHeader("Location", b.url("/login").toString()))
            start()
        }
        try {
            val e = runCatching { runBlocking { AbsLoginClient().login(a.url("/"), LoginRequest("u", "pw")) } }.exceptionOrNull()
            assertTrue("expected HttpException(307), got $e", e is HttpException && e.code() == 307)
            assertEquals(1, a.requestCount)
            assertEquals(0, b.requestCount)
        } finally { a.shutdown(); b.shutdown() }
    }
}
