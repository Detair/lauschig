package io.wolftown.lauschig.connection

import io.wolftown.lauschig.account.Account
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * The connection of the signed-in account's server, for `ApiProvider`. Every server (a new account, changed addresses)
 * starts with `null` – "reset, checking" – before its own probe answers, so the shared state never keeps the previous
 * server's Online (whose host would then get the new account's token). A new probe loop starts only when the server
 * or its addresses change, not on other account updates. Signed out: [ConnectionState.Offline].
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun accountConnection(accounts: Flow<Account?>, states: (ServerAddresses) -> Flow<ConnectionState>): Flow<ConnectionState?> =
    accounts.map { a -> a?.let { it.serverId to it.addresses } }
        .distinctUntilChanged()
        .flatMapLatest { server ->
            if (server == null) flowOf(ConnectionState.Offline)
            else states(server.second).onStart<ConnectionState?> { emit(null) }
        }
