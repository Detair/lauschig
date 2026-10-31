package io.wolftown.lauschig.ui.setup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
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
        .build()

    override suspend fun login(baseUrl: HttpUrl, request: LoginRequest): LoginResponse =
        AbsClientFactory.retrofit(baseUrl, client).create(AuthApi::class.java).login(request)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LoginModule {
    @Binds abstract fun loginClient(impl: AbsLoginClient): LoginClient
}
