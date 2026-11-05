package io.wolftown.lauschig.account

import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.connection.AddressResolver
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.connection.belongsTo
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Best-effort `POST /logout` on sign-out: the server invalidates this device's refresh token (ABS 2.37.1 `Auth.js`,
 * token in `x-refresh-token`). Only to the account's server, over the guarded client without credentials; at most
 * [timeoutMs]. Never throws (except cancellation): signing out never depends on it.
 */
class ServerLogout(
    private val connection: () -> ConnectionState,
    client: OkHttpClient,
    private val timeoutMs: Long = TIMEOUT_MS,
    /** Which of these addresses answers now; for a saved profile, whose server need not be the connection's. */
    private val resolve: suspend (ServerAddresses) -> ConnectionState = { ConnectionState.Offline },
) {
    @Inject constructor(api: ApiProvider, resolver: AddressResolver) :
        this({ api.connectionState.value }, api.unauthenticatedClient(), resolve = resolver::resolveElsewhere)

    private val client = client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** True if the server confirmed. */
    suspend fun logout(account: Account, refreshToken: String): Boolean {
        val online = connection() as? ConnectionState.Online ?: return false
        if (!online.belongsTo(account.addresses)) return false
        return bounded { post(online, refreshToken) }
    }

    /**
     * The same for a saved profile that is removed, whose server may be another one than the signed-in account's: if the
     * connection is not on its server, its own addresses are asked first. All of it within [timeoutMs]. True if the server
     * confirmed; if none answers its session runs out on its own.
     */
    suspend fun logoutProfile(account: Account, refreshToken: String): Boolean = bounded {
        val online = (connection() as? ConnectionState.Online)?.takeIf { it.belongsTo(account.addresses) }
            ?: resolve(account.addresses) as? ConnectionState.Online
            ?: return@bounded false
        post(online, refreshToken)
    }

    private suspend fun post(online: ConnectionState.Online, refreshToken: String): Boolean {
        AbsClientFactory.retrofit(online.baseUrl, client).create(AuthApi::class.java).logout(refreshToken)
        return true
    }

    private suspend fun bounded(block: suspend () -> Boolean): Boolean =
        try {
            withTimeoutOrNull(timeoutMs) { block() } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "server logout failed: ${e.javaClass.simpleName}")
            false
        }

    private companion object {
        const val TAG = "ServerLogout"
        const val TIMEOUT_MS = 5_000L
    }
}
