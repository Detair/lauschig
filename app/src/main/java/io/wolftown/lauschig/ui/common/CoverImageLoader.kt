package io.wolftown.lauschig.ui.common

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
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
import okio.Path.Companion.toOkioPath

/**
 * Coil's network requests: the guarded client without credentials, so plain HTTP still only goes to a server the signed-in
 * account confirmed. Nothing Coil loads needs a token. The server's covers ([coverModel]) are public, like its author images,
 * from the oldest server this app works with on: `Auth.ignorePatterns` in Audiobookshelf 2.26.0 (commit 264ae928), and checked
 * on 2.37.1. The rest are third-party images (Audible's). So a tile cannot send anyone's token, also not the next account's in
 * the moments around a profile switch.
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
        // Keeps the shared connection state current while visible: the pages build their cover addresses from it.
        scope.launch {
            visible.distinctUntilChanged().collectLatest { if (it) api.connection.collect {} }
        }
    }

    override fun newCall(request: Request): Call = api.unauthenticatedClient().newCall(request)
}

/** True while the app process is at least STARTED (some activity visible). */
internal fun processStarted(): Flow<Boolean> = callbackFlow {
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    val observer = LifecycleEventObserver { _, _ -> trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    lifecycle.addObserver(observer)
    awaitClose { lifecycle.removeObserver(observer) }
}.flowOn(Dispatchers.Main.immediate)

/** Subfolder of the app's cache dir holding the cover images (Android may reclaim it under storage pressure; covers are re-fetched then). */
const val COVER_CACHE_DIR = "coil_covers"
const val COVER_CACHE_MAX_BYTES = 150L * 1024 * 1024

/**
 * Explicit cover disk cache: a known directory and a fixed 150 MB ceiling (Coil's default depends on free space and
 * lives in `image_cache`). Covers load from here after an app restart and while offline. Sign-out empties it through
 * `SingletonImageLoader.get(context).diskCache?.clear()` (AccountStateCleaner).
 */
fun newCoverDiskCache(context: Context): DiskCache = DiskCache.Builder()
    .directory(context.cacheDir.resolve(COVER_CACHE_DIR).toOkioPath())
    .maxSizeBytes(COVER_CACHE_MAX_BYTES)
    .build()

/** Where Coil 3 kept its default disk cache before [newCoverDiskCache] (seen on a 0.3.0 install: `coil3_disk_cache`; `image_cache` is Coil 2's). */
private val LEGACY_COVER_CACHE_DIRS = listOf("coil3_disk_cache", "image_cache")

/**
 * Deletes the previous default Coil cache directories: nothing writes there any more, so they would keep covers of
 * earlier accounts (and up to a few hundred MB) forever. Idempotent; a missing directory is fine; never throws.
 * Blocking file I/O: call it off the main thread.
 */
fun deleteLegacyCoverCaches(context: Context) {
    for (name in LEGACY_COVER_CACHE_DIRS) runCatching { context.cacheDir.resolve(name).deleteRecursively() }
}

fun newCoverImageLoader(context: Context, api: ApiProvider): ImageLoader {
    val calls = GuardedCallFactory(api, CoroutineScope(SupervisorJob() + Dispatchers.Default), processStarted())
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { calls })) }
        .diskCache { newCoverDiskCache(context) }
        .build()
}
