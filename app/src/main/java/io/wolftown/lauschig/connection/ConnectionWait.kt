package io.wolftown.lauschig.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a start (car, voice, playback resumption, a tap in the app) waits for the first connection check of a cold
 * start. The probe tries an unreachable home address for up to 3 s before the public one, so a fixed short wait
 * would call a reachable server "offline" on the first drive.
 */
const val CONNECTION_ANSWER_WAIT_MS = 10_000L

/**
 * True if the server is online; while the first check since the start is still running ([checking], see
 * `ApiProvider.checking`) it waits for its answer, at most [maxMs]. Unlike a fixed short wait this never calls a
 * slow first probe (an unreachable local address, then the public one) "offline".
 */
suspend fun awaitConnectionAnswer(connection: Flow<ConnectionState>, checking: Flow<Boolean>, maxMs: Long): Boolean =
    awaitOnlineAnswer(connection, checking, maxMs) != null

/**
 * The online state (the server to stream from) once the first check answered, or null: offline (no waiting when the
 * check already answered "offline"), or no answer within [maxMs].
 */
suspend fun awaitOnlineAnswer(connection: Flow<ConnectionState>, checking: Flow<Boolean>, maxMs: Long): ConnectionState.Online? =
    withTimeoutOrNull(maxMs) {
        combine(connection, checking) { state, pending -> state to pending }
            .first { (state, pending) -> state is ConnectionState.Online || !pending }
            .first as? ConnectionState.Online
    }
