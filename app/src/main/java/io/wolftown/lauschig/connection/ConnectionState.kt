package io.wolftown.lauschig.connection

import okhttp3.HttpUrl

sealed interface ConnectionState {
    data class Online(val baseUrl: HttpUrl, val via: Via) : ConnectionState
    data object Offline : ConnectionState
    enum class Via { LOCAL, PUBLIC }
}

/**
 * True if this state is about one of [addresses] (same scheme, host and port): a state of the current account's
 * server. Right after an account switch a shared state may still replay the previous server's Online for a moment.
 */
fun ConnectionState.Online.belongsTo(addresses: ServerAddresses): Boolean =
    listOfNotNull(addresses.local, addresses.public).any { baseUrl.scheme == it.scheme && baseUrl.host == it.host && baseUrl.port == it.port }
