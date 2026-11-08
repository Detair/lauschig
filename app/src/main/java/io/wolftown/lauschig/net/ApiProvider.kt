package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.me.MeApi
import io.wolftown.lauschig.absapi.playback.PlaybackSessionApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
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
    private val baseClient: OkHttpClient,
    private val store: AccountStore,
    scope: CoroutineScope,
) {
    // Network interceptor: sees every hop incl. redirects. Every client (also the refresh client) refuses http:// to servers the user has not confirmed.
    private val okHttp: OkHttpClient = baseClient.newBuilder().addNetworkInterceptor(CleartextGuardInterceptor(store)).build()

    /** Per (account, base URL): a client belongs to the account signed in when it was built ([AuthInterceptor]). */
    private val cache = HashMap<Pair<String?, HttpUrl>, Retrofit>()
    private val clients = HashMap<Pair<String?, HttpUrl>, OkHttpClient>()
    /** One refresh lock per account, shared by its clients for the local and the public address. */
    private val refreshLocks = HashMap<String?, Any>()

    // Shared so that several API flows do not each run their own probe loop. When nobody has listened for 5 s the
    // value falls back to Offline: a process kept alive at home must not hand an old Online(LOCAL) to the next
    // listener (e.g. the car starting the playback service) before a fresh probe answered.
    private val firstCheck = MutableStateFlow(true)
    private val shared = connection
        .onStart { firstCheck.value = true }
        // An answer ends the check only once it is published: whoever sees the check end finds the answer in the state.
        .transform {
            if (it == null) firstCheck.value = true
            emit(it ?: ConnectionState.Offline)
            if (it != null) firstCheck.value = false
        }
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

    /** The signed-in account's server id; null when signed out or unreadable (its clients then send no token). */
    private val signedInId: Flow<String?> = store.account
        .map { it?.serverId }
        .catch { e -> if (e is CancellationException) throw e else emit(null) }
        .distinctUntilChanged()

    /** A connection's client and the account it belongs to. */
    private data class Bound(val owner: String?, val retrofit: Retrofit)

    /**
     * The connection together with the signed-in account: a collector that stays gets the new account's client at the
     * switch itself, also when it never sees the connection reset (the shared state may skip it).
     */
    private val retrofit: Flow<Bound?> = combine(shared, signedInId) { state, owner ->
        (state as? ConnectionState.Online)?.let { Bound(owner, retrofitFor(owner, it.baseUrl)) }
    }.distinctUntilChanged()

    val authApi: Flow<AuthApi?> = retrofit.map { it?.retrofit?.create(AuthApi::class.java) }

    /**
     * The API of account [serverId]: null while offline and whenever [serverId] is not the signed-in account. Whoever
     * works for one account asks with its id, so work that outlives a switch gets no API, instead of the next account's.
     * An API it already holds stops sending at the switch ([AuthInterceptor]).
     */
    fun libraryApi(serverId: String): Flow<LibraryApi?> = apiOf(serverId, LibraryApi::class.java)
    fun meApi(serverId: String): Flow<MeApi?> = apiOf(serverId, MeApi::class.java)
    fun adminApi(serverId: String): Flow<AdminApi?> = apiOf(serverId, AdminApi::class.java)
    fun playbackSessionApi(serverId: String): Flow<PlaybackSessionApi?> = apiOf(serverId, PlaybackSessionApi::class.java)

    private fun <T : Any> apiOf(serverId: String, type: Class<T>): Flow<T?> =
        retrofit.map { bound -> bound?.takeIf { it.owner == serverId }?.retrofit?.create(type) }

    /** Guarded client without credentials: what needs no account (the covers), and requests while no server is online. */
    fun unauthenticatedClient(): OkHttpClient = okHttp

    /**
     * A client without credentials for what concerns [account] but not the signed-in one (the logout of a removed profile):
     * plain http:// only to the servers the user confirmed for [account].
     */
    fun unauthenticatedClient(account: Account): OkHttpClient =
        baseClient.newBuilder().addNetworkInterceptor(CleartextGuardInterceptor { account.allowCleartext }).build()

    /**
     * The authenticated client of account [serverId] for [baseUrl] (origin-scoped Bearer + token refresh); also used for
     * file downloads. Its requests carry that account's token, and they go out only while it is the signed-in account
     * and [baseUrl] is one of its addresses ([AuthInterceptor]).
     */
    @Synchronized
    fun clientFor(serverId: String?, baseUrl: HttpUrl): OkHttpClient = clients.getOrPut(serverId to baseUrl) {
        okHttp.newBuilder()
            .addInterceptor(AuthInterceptor(store, baseUrl, serverId))
            .authenticator(TokenAuthenticator(store, okHttp, baseUrl, serverId, refreshLocks.getOrPut(serverId) { Any() }))
            .build()
    }

    @Synchronized
    private fun retrofitFor(serverId: String?, baseUrl: HttpUrl): Retrofit = cache.getOrPut(serverId to baseUrl) {
        AbsClientFactory.retrofit(baseUrl, clientFor(serverId, baseUrl))
    }
}
