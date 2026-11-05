package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <access>` when a token is stored and the request targets [baseUrl]'s origin, and tags the
 * request with the account it went out for ([SentBy]), so a 401 after a profile switch is never answered with the
 * other account's token ([TokenAuthenticator]). Runs on OkHttp's I/O threads.
 */
class AuthInterceptor(private val store: AccountStore, private val baseUrl: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = if (chain.request().url.sameOrigin(baseUrl)) runBlocking { store.credentials() } else null
        val token = credentials?.accessToken
        val request = if (token == null) chain.request()
        else chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .tag(SentBy::class.java, SentBy(credentials.serverId))
            .build()
        return chain.proceed(request)
    }
}

/** The account (server id) whose token a request carries. */
class SentBy(val serverId: String)
