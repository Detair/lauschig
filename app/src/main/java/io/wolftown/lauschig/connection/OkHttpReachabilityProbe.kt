package io.wolftown.lauschig.connection

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.auth.AuthApi
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.HttpException
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

    override suspend fun isReachable(url: HttpUrl): Boolean = check(url).ok

    /** The failure as "<exception>: <message>" or "HTTP <code>"; the ping is unauthenticated, nothing secret in it. */
    override suspend fun check(url: HttpUrl): ProbeOutcome = try {
        if (AbsClientFactory.retrofit(url, client).create(AuthApi::class.java).ping().success) ProbeOutcome(true)
        else ProbeOutcome(false, "ping: success=false")
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        ProbeOutcome(false, "HTTP ${e.code()}")
    } catch (e: Exception) {
        ProbeOutcome(false, listOfNotNull(e.javaClass.simpleName, e.message?.take(MAX_ERROR)).joinToString(": "))
    }

    private companion object {
        const val MAX_ERROR = 200
    }
}
