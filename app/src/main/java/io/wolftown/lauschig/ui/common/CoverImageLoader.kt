package io.wolftown.lauschig.ui.common

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Routes Coil's network requests through [ApiProvider]'s authenticated, cleartext-guarded client for the
 * currently online server (Bearer token, origin-scoped). While offline it falls back to the guarded client
 * without credentials, which cannot leak a token; the UI does not request covers while offline anyway.
 */
class GuardedCallFactory(private val api: ApiProvider, scope: CoroutineScope) : Call.Factory {
    @Volatile private var online: OkHttpClient? = null

    init {
        api.connection.onEach { state ->
            online = (state as? ConnectionState.Online)?.let { api.clientFor(it.baseUrl) }
        }.launchIn(scope)
    }

    override fun newCall(request: Request): Call = (online ?: api.unauthenticatedClient()).newCall(request)
}

fun newCoverImageLoader(context: Context, api: ApiProvider): ImageLoader {
    val calls = GuardedCallFactory(api, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { calls })) }
        .build()
}
