package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.isAddressOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Adds `Authorization: Bearer <access>` to requests for [baseUrl]'s origin – the token of the account the client was
 * made for ([madeFor], the one signed in when [ApiProvider.clientFor] built it), and only while that account is the
 * signed-in one and [baseUrl] is one of its addresses. Otherwise the request is not sent at all
 * ([AccountChangedException]): after a profile switch, a re-auth as another user or a sign-out, a request made before
 * never carries the new account's token and never reaches a server as someone else; and no token goes to a server
 * that is not the account's (a stale "online" state of the previous server). [madeFor] null (built while signed out):
 * no token. Runs on OkHttp's I/O threads.
 */
class AuthInterceptor(private val store: AccountStore, private val baseUrl: HttpUrl, private val madeFor: String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (madeFor == null || !request.url.sameOrigin(baseUrl)) return chain.proceed(request)
        val (credentials, account) = runBlocking { store.credentials() to store.account.first() }
        if (credentials?.serverId != madeFor || account?.serverId != madeFor) throw AccountChangedException("made for another account")
        if (!baseUrl.isAddressOf(account.addresses)) throw AccountChangedException("not a server of this account")
        val token = credentials.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** A request made for an account that is no longer the signed-in one (or for a server that is not its own): not sent. */
class AccountChangedException(message: String) : IOException(message)
