package io.wolftown.lauschig.ui.setup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.net.CleartextNotConfirmedException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Logs in against a server address typed in setup (there is no account yet, so no [io.wolftown.lauschig.net.ApiProvider]). */
interface LoginClient {
    suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse
}

/** Plain OkHttp client (no auth, no authenticator); 5 s overall per address so a dead local address falls through quickly. */
class AbsLoginClient @Inject constructor() : LoginClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        // Credentials are sent exactly once, to the typed address: a redirect (e.g. https -> http) is never followed.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse {
        // Plain HTTP only to the very address that was confirmed for this attempt.
        val guarded = client.newBuilder().addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps && url.cleartextKey() != baseUrl.cleartextKey()) throw CleartextNotConfirmedException(url.host)
            chain.proceed(chain.request())
        }.build()
        return AbsClientFactory.retrofit(baseUrl, guarded).create(AuthApi::class.java).login(request)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LoginModule {
    @Binds abstract fun loginClient(impl: AbsLoginClient): LoginClient
}
