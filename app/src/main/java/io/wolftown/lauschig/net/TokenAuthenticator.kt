package io.wolftown.lauschig.net

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.account.AccountStore
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
 * - Concurrent 401s are serialized; later ones reuse the token the first one obtained.
 * - Tokens belong to the account that sent the request ([SentBy]): another account active by now (profile switch) means
 *   no retry and no refresh; a refresh that was already running stores its tokens with its own account, wherever it is.
 *
 * [refreshClient] must NOT contain this authenticator (avoids recursion). Redirects are never followed for the
 * refresh call: a 3xx would replay the refresh token (307/308 keep the body) to wherever the Location points.
 */
class TokenAuthenticator(
    private val store: AccountStore,
    refreshClient: OkHttpClient,
    private val baseUrl: HttpUrl,
) : Authenticator {
    private val authApi = AbsClientFactory.retrofit(
        baseUrl,
        refreshClient.newBuilder().followRedirects(false).followSslRedirects(false).build(),
    ).create(AuthApi::class.java)
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // already retried once
        if (!response.request.url.sameOrigin(baseUrl)) return null // never send the ABS token elsewhere
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        return synchronized(lock) { runBlocking { refreshAndRetry(response.request, sentToken) } }
    }

    private suspend fun refreshAndRetry(request: Request, sentToken: String?): Request? {
        val now = store.credentials()
        val sentBy = request.tag(SentBy::class.java)?.serverId
        if (sentBy != null && now?.serverId != sentBy) return null // another account (or none) since: not ours to answer
        val current = now?.accessToken
        if (current != null && current != sentToken) return request.withToken(current) // someone else refreshed
        val refresh = now?.refreshToken
        if (refresh == null) {
            // Signed in but no usable refresh token (e.g. undecryptable after keystore loss): ask for re-login.
            if (now != null) store.markNeedsReauth(now.serverId)
            return null
        }
        val user = try {
            authApi.refresh(refresh).user
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) store.markNeedsReauth(now.serverId)
            return null
        } catch (_: Exception) {
            return null
        }
        val access = user.accessToken ?: return null
        try {
            store.updateTokens(now.serverId, access, user.refreshToken ?: refresh)
        } catch (_: Exception) {
            return null // storage failure: no crash, no reauth flag
        }
        // Switched while refreshing: the tokens went to their account (now set aside); its request is not sent again.
        if (store.account.first()?.serverId != now.serverId) return null
        return request.withToken(access)
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()
}

/** True when scheme, host and port match, i.e. the request goes to the same server origin. */
internal fun HttpUrl.sameOrigin(other: HttpUrl) =
    scheme == other.scheme && host == other.host && port == other.port
