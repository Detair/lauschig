package io.wolftown.lauschig.account

import android.util.Log
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.belongsTo
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Best-effort `POST /logout` on sign-out: the server invalidates this device's refresh token (ABS 2.37.1 `Auth.js`,
 * token in `x-refresh-token`). Only to the account's server when it is online right now, over the guarded client
 * without credentials; at most [timeoutMs]. Never throws (except cancellation): signing out never depends on it.
 */
class ServerLogout(
    private val connection: () -> ConnectionState,
    client: OkHttpClient,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    @Inject constructor(api: ApiProvider) : this({ api.connectionState.value }, api.unauthenticatedClient())

    private val client = client.newBuilder()
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** True if the server confirmed. */
    suspend fun logout(account: Account, refreshToken: String): Boolean {
        val online = connection() as? ConnectionState.Online ?: return false
        if (!online.belongsTo(account.addresses)) return false
        return try {
            withTimeoutOrNull(timeoutMs) {
                AbsClientFactory.retrofit(online.baseUrl, client).create(AuthApi::class.java).logout(refreshToken)
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "server logout failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TAG = "ServerLogout"
        const val TIMEOUT_MS = 5_000L
    }
}
