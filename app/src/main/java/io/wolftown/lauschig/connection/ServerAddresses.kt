package io.wolftown.lauschig.connection

import okhttp3.HttpUrl

data class ServerAddresses(
    val local: HttpUrl?,
    val public: HttpUrl?,
    val homeSsids: Set<String> = emptySet(),
)
