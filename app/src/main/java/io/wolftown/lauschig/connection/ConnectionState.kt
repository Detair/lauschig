package io.wolftown.lauschig.connection

import okhttp3.HttpUrl

sealed interface ConnectionState {
    data class Online(val baseUrl: HttpUrl, val via: Via) : ConnectionState
    data object Offline : ConnectionState
    enum class Via { LOCAL, PUBLIC }
}
