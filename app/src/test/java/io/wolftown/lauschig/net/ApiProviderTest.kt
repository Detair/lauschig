package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApiProviderTest {
    @Test
    fun twoCollectors_causeSingleUpstreamSubscription() = runTest(UnconfinedTestDispatcher()) {
        var subscriptions = 0
        val upstream = flow {
            subscriptions++
            emit(ConnectionState.Online("http://abs.test/".toHttpUrl(), ConnectionState.Via.PUBLIC))
            awaitCancellation()
        }
        val provider = ApiProvider(upstream, OkHttpClient(), FakeAccountStore(), backgroundScope)
        var auth: AuthApi? = null
        var lib: LibraryApi? = null
        backgroundScope.launch { provider.authApi.collect { auth = it } }
        backgroundScope.launch { provider.libraryApi.collect { lib = it } }
        assertEquals(1, subscriptions)
        assertNotNull(auth)
        assertNotNull(lib)
    }
}
