package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.AccountStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Adds `Authorization: Bearer <access>` when a token is stored and the request targets [baseUrl]'s origin. Runs on OkHttp's I/O threads. */
class AuthInterceptor(private val store: AccountStore, private val baseUrl: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = if (chain.request().url.sameOrigin(baseUrl)) runBlocking { store.accessToken() } else null
        val request = if (token == null) chain.request()
        else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        return chain.proceed(request)
    }
}
