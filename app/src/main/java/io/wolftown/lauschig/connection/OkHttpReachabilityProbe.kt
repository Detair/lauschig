package io.wolftown.lauschig.connection

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `GET ping`: 3 s to connect (an unreachable address fails fast), 8 s overall, so a link saturated by a running
 * download still counts as reachable. Any failure counts as unreachable.
 */
@Singleton
class OkHttpReachabilityProbe @Inject constructor() : ReachabilityProbe {
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
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
