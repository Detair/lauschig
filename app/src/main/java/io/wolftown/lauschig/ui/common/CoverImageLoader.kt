package io.wolftown.lauschig.ui.common

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request

/**
 * Routes Coil's network requests through [ApiProvider]'s authenticated, cleartext-guarded client for the
 * currently online server (resolved per request, never stale) (Bearer token, origin-scoped). While offline it falls back to the guarded client
 * without credentials, which cannot leak a token; the UI does not request covers while offline anyway.
 *
 * The shared connection state is kept active only while [visible] (the app is in the foreground), so the
 * probe loop does not keep running in the background just for covers.
 */
class GuardedCallFactory(
    private val api: ApiProvider,
    scope: CoroutineScope,
    visible: Flow<Boolean>,
) : Call.Factory {
    init {
        // Keeps the shared connection state current while visible; resolution itself is synchronous.
        scope.launch {
            visible.distinctUntilChanged().collectLatest { if (it) api.connection.collect {} }
        }
    }

    override fun newCall(request: Request): Call {
        val state = api.connectionState.value
        val client = (state as? ConnectionState.Online)?.let { api.clientFor(it.baseUrl) } ?: api.unauthenticatedClient()
        return client.newCall(request)
    }
}

/** True while the app process is at least STARTED (some activity visible). */
internal fun processStarted(): Flow<Boolean> = callbackFlow {
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    val observer = LifecycleEventObserver { _, _ -> trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    lifecycle.addObserver(observer)
    awaitClose { lifecycle.removeObserver(observer) }
}.flowOn(Dispatchers.Main.immediate)

fun newCoverImageLoader(context: Context, api: ApiProvider): ImageLoader {
    val calls = GuardedCallFactory(api, CoroutineScope(SupervisorJob() + Dispatchers.Default), processStarted())
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { calls })) }
        .build()
}
