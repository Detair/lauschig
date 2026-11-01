package io.wolftown.lauschig.connection

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** `GET ping` with a 3 s overall timeout; any failure counts as unreachable. */
@Singleton
class OkHttpReachabilityProbe @Inject constructor() : ReachabilityProbe {
    private val client = OkHttpClient.Builder()
        .callTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    override suspend fun isReachable(url: HttpUrl): Boolean = try {
        AbsClientFactory.retrofit(url, client).create(AuthApi::class.java).ping().success
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
