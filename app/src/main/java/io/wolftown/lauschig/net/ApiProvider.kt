package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * Exposes API interfaces only while online (`null` when [ConnectionState.Offline]).
 * [okHttp] is the base client; per base URL it is extended with [AuthInterceptor] and a
 * [TokenAuthenticator] whose refresh call uses [okHttp] itself (no authenticator, no recursion, no redirects).
 */
class ApiProvider(
    /** The account server's connection; `null` = a new server, reset to Offline and checking (see `accountConnection`). */
    connection: Flow<ConnectionState?>,
    okHttp: OkHttpClient,
    private val store: AccountStore,
    scope: CoroutineScope,
) {
    // Network interceptor: sees every hop incl. redirects. Every client (also the refresh client) refuses http:// to servers the user has not confirmed.
    private val okHttp: OkHttpClient = okHttp.newBuilder().addNetworkInterceptor(CleartextGuardInterceptor(store)).build()

    private val cache = HashMap<HttpUrl, Retrofit>()
    private val clients = HashMap<HttpUrl, OkHttpClient>()

    // Shared so that several API flows do not each run their own probe loop. When nobody has listened for 5 s the
    // value falls back to Offline: a process kept alive at home must not hand an old Online(LOCAL) to the next
    // listener (e.g. the car starting the playback service) before a fresh probe answered.
    private val firstCheck = MutableStateFlow(true)
    private val shared = connection
        .onStart { firstCheck.value = true }
        .onEach { firstCheck.value = it == null }
        .map { it ?: ConnectionState.Offline }
        .onCompletion { firstCheck.value = true }
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), ConnectionState.Offline)

    /**
     * True while the shared probe loop has not answered yet since it (re)started or the server changed: its
     * [ConnectionState.Offline] is then only the initial value, not a result. For the UI's offline indicator only; the state itself is unchanged.
     */
    val checking: StateFlow<Boolean> = firstCheck

    /** The shared connection state (one probe loop for everyone). */
    val connection: Flow<ConnectionState> get() = shared

    /**
     * Same shared state with a synchronous [StateFlow.value]: [ConnectionState.Offline] until something collects, and
     * again once nothing has collected for a few seconds (never an old value from before).
     */
    val connectionState: StateFlow<ConnectionState> get() = shared

    private val retrofit: Flow<Retrofit?> = shared
        .map { (it as? ConnectionState.Online)?.let { s -> retrofitFor(s.baseUrl) } }
        .distinctUntilChanged()

    val authApi: Flow<AuthApi?> = retrofit.map { it?.create(AuthApi::class.java) }
    val libraryApi: Flow<LibraryApi?> = retrofit.map { it?.create(LibraryApi::class.java) }
    val meApi: Flow<MeApi?> = retrofit.map { it?.create(MeApi::class.java) }
    val adminApi: Flow<AdminApi?> = retrofit.map { it?.create(AdminApi::class.java) }

    /** Guarded client without credentials, for requests while no server is online (they fail fast). */
    fun unauthenticatedClient(): OkHttpClient = okHttp

    /** The authenticated client for [baseUrl] (origin-scoped Bearer + token refresh); also used for file downloads. */
    @Synchronized
    fun clientFor(baseUrl: HttpUrl): OkHttpClient = clients.getOrPut(baseUrl) {
        okHttp.newBuilder()
            .addInterceptor(AuthInterceptor(store, baseUrl))
            .authenticator(TokenAuthenticator(store, okHttp, baseUrl))
            .build()
    }

    @Synchronized
    private fun retrofitFor(baseUrl: HttpUrl): Retrofit = cache.getOrPut(baseUrl) {
        AbsClientFactory.retrofit(baseUrl, clientFor(baseUrl))
    }
}
