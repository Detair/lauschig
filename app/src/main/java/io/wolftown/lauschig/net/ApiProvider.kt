package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Exposes API interfaces only while online (`null` when [ConnectionState.Offline]).
 * [okHttp] is the base client; per base URL it is extended with [AuthInterceptor] and a
 * [TokenAuthenticator] whose refresh call uses [okHttp] itself (no authenticator, no recursion).
 */
class ApiProvider(
    connection: Flow<ConnectionState>,
    private val okHttp: OkHttpClient,
    private val store: AccountStore,
    scope: CoroutineScope,
) {
    private val cache = HashMap<HttpUrl, Retrofit>()

    // Shared so that several API flows do not each run their own probe loop.
    private val shared = connection.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Offline)

    /** The shared connection state (one probe loop for everyone). */
    val connection: Flow<ConnectionState> get() = shared

    private val retrofit: Flow<Retrofit?> = shared
        .map { (it as? ConnectionState.Online)?.let { s -> retrofitFor(s.baseUrl) } }
        .distinctUntilChanged()

    val authApi: Flow<AuthApi?> = retrofit.map { it?.create(AuthApi::class.java) }
    val libraryApi: Flow<LibraryApi?> = retrofit.map { it?.create(LibraryApi::class.java) }
    val meApi: Flow<MeApi?> = retrofit.map { it?.create(MeApi::class.java) }

    @Synchronized
    private fun retrofitFor(baseUrl: HttpUrl): Retrofit = cache.getOrPut(baseUrl) {
        val client = okHttp.newBuilder()
            .addInterceptor(AuthInterceptor(store, baseUrl))
            .authenticator(TokenAuthenticator(store, okHttp, baseUrl))
            .build()
        AbsClientFactory.retrofit(baseUrl, client)
    }
}
