package io.wolftown.lauschig.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * True if the server is online; while the first check since the start is still running ([checking], see
 * `ApiProvider.checking`) it waits for its answer, at most [maxMs]. Unlike a fixed short wait this never calls a
 * slow first probe (an unreachable local address, then the public one) "offline".
 */
suspend fun awaitConnectionAnswer(connection: Flow<ConnectionState>, checking: Flow<Boolean>, maxMs: Long): Boolean =
    withTimeoutOrNull(maxMs) {
        combine(connection, checking) { state, pending -> state to pending }
            .first { (state, pending) -> state is ConnectionState.Online || !pending }
            .first is ConnectionState.Online
    } ?: false
