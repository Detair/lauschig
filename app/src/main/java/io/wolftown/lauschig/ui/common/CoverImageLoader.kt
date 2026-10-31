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
import okhttp3.Call
import okhttp3.Request

/**
 * Routes Coil's network requests through [ApiProvider]'s authenticated, cleartext-guarded client for the
 * currently online server (resolved per request, never stale) (Bearer token, origin-scoped). While offline it falls back to the guarded client
 * without credentials, which cannot leak a token; the UI does not request covers while offline anyway.
 */
class GuardedCallFactory(private val api: ApiProvider, scope: CoroutineScope) : Call.Factory {
    init {
        // Keeps the shared connection state active so its value is current; resolution itself is synchronous.
        api.connection.launchIn(scope)
    }

    override fun newCall(request: Request): Call {
        val state = api.connectionState.value
        val client = (state as? ConnectionState.Online)?.let { api.clientFor(it.baseUrl) } ?: api.unauthenticatedClient()
        return client.newCall(request)
    }
}

fun newCoverImageLoader(context: Context, api: ApiProvider): ImageLoader {
    val calls = GuardedCallFactory(api, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { calls })) }
        .build()
}
