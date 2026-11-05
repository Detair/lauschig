package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.isAddressOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException

/**
 * On 401 refreshes the tokens once and retries the request.
 *
 * - Refresh rejected (401/403): [AccountStore.markNeedsReauth]; account and data stay untouched.
 * - Network error during refresh: nothing is flagged, the 401 just propagates.
 * - Concurrent 401s are serialized – per account, across its addresses ([lock]); later ones reuse the token the first one
 *   obtained.
 * - Only for the account the client was made for ([madeFor], see [AuthInterceptor]), while it is the signed-in one and
 *   [baseUrl] is one of its addresses: after a switch nothing is retried or refreshed (and nobody is flagged), and no
 *   refresh token goes to another account's server. A refresh that was already running stores its tokens with its own
 *   account ([AccountStore.updateTokens] by server id), wherever that account is by then.
 *
 * [refreshClient] must NOT contain this authenticator (avoids recursion). Redirects are never followed for the
 * refresh call: a 3xx would replay the refresh token (307/308 keep the body) to wherever the Location points.
 */
class TokenAuthenticator(
    private val store: AccountStore,
    refreshClient: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val madeFor: String?,
    /**
     * Serialises refreshes; shared by all clients of one account (its local and its public address), so two 401s on
     * different addresses never refresh twice with the same refresh token.
     */
    private val lock: Any = Any(),
) : Authenticator {
    private val authApi = AbsClientFactory.retrofit(
        baseUrl,
        refreshClient.newBuilder().followRedirects(false).followSslRedirects(false).build(),
    ).create(AuthApi::class.java)

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        if (madeFor == null) return null // no account's token went out
        if (!response.request.url.sameOrigin(baseUrl)) return null // never send the ABS token elsewhere
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        return synchronized(lock) { runBlocking { refreshAndRetry(response.request, sentToken) } }
    }

    private suspend fun refreshAndRetry(request: Request, sentToken: String?): Request? {
        val owner = madeFor ?: return null
        val now = store.credentials()
        val account = store.account.first()
        // Another account (or none) since, or not its server: not ours to answer.
        if (now?.serverId != owner || account?.serverId != owner || !baseUrl.isAddressOf(account.addresses)) return null
        val current = now.accessToken
        if (current != null && current != sentToken) return request.withToken(current) // someone else refreshed
        val refresh = now.refreshToken
        if (refresh == null) {
            // Signed in but no usable refresh token (e.g. undecryptable after keystore loss): ask for re-login.
            store.markNeedsReauth(owner)
            return null
        }
        val user = try {
            authApi.refresh(refresh).user
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) store.markNeedsReauth(owner)
            return null
        } catch (_: Exception) {
            return null
        }
        val access = user.accessToken ?: return null
        try {
            store.updateTokens(owner, access, user.refreshToken ?: refresh)
        } catch (_: Exception) {
            return null // storage failure: no crash, no reauth flag
        }
        // Switched while refreshing: the tokens went to their account (now set aside); its request is not sent again.
        if (store.account.first()?.serverId != owner) return null
        return request.withToken(access)
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()
}

/** True when scheme, host and port match, i.e. the request goes to the same server origin. */
internal fun HttpUrl.sameOrigin(other: HttpUrl) =
    scheme == other.scheme && host == other.host && port == other.port
