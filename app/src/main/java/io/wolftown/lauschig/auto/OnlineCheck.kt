package io.wolftown.lauschig.auto

import io.wolftown.lauschig.connection.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * True if the server is (or comes) online within [waitMs]. A voice or car request right after a cold start arrives
 * before the first reachability probe answered (the shared state starts as Offline); deciding then would treat every
 * streamed title as unavailable. Only the shared connection flow is asked, so no extra probe loop starts.
 */
suspend fun awaitOnline(connection: Flow<ConnectionState>, waitMs: Long): Boolean =
    withTimeoutOrNull(waitMs) { connection.firstOrNull { it is ConnectionState.Online } } != null
