package io.wolftown.lauschig.absapi

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object AbsClientFactory {
    /**
     * Builds a Retrofit instance for an Audiobookshelf server. [baseUrl] may carry a sub-path
     * (`https://host/abs`); a trailing slash is added when missing so relative endpoint paths resolve under it.
     */
    fun retrofit(baseUrl: HttpUrl, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl.withTrailingSlash())
            .client(client)
            .addConverterFactory(AbsJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build()
}

internal fun HttpUrl.withTrailingSlash(): HttpUrl =
    if (encodedPath.endsWith("/")) this else newBuilder().addPathSegment("").build()
