package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.cleartextKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Thrown instead of sending a request over plain HTTP to a server the user has not confirmed. */
class CleartextNotConfirmedException(host: String) : IOException("Cleartext HTTP to $host was not confirmed by the user")

/**
 * The platform permits cleartext globally (the local server is usually `http://`); this is where the app
 * enforces that only servers in `Account.allowCleartext` (confirmed in the setup dialog) get plain HTTP.
 * Register with addNetworkInterceptor so redirect hops are checked too. Reads the local account store (no network); runs on OkHttp's I/O threads.
 */
class CleartextGuardInterceptor(private val store: AccountStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps) {
            val allowed = runBlocking { store.account.first()?.allowCleartext }.orEmpty()
            if (url.cleartextKey() !in allowed) throw CleartextNotConfirmedException(url.host)
        }
        return chain.proceed(chain.request())
    }
}
