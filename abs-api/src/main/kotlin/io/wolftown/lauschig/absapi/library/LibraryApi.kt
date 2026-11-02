package io.wolftown.lauschig.absapi.library

import io.wolftown.lauschig.absapi.withTrailingSlash
import okhttp3.HttpUrl
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface LibraryApi {
    @GET("api/libraries")
    suspend fun libraries(): LibrariesResponse

    @GET("api/libraries/{id}/items")
    suspend fun items(
        @Path("id") libraryId: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int,
        @Query("minified") minified: Int = 1,
    ): LibraryItemsPage

    /** All authors of a library (`{ "authors": [...] }`); used for the optional author image and bio. */
    @GET("api/libraries/{id}/authors")
    suspend fun authors(@Path("id") id: String): AuthorsResponse

    /** Newest unheard episodes of a podcast library (404 for book libraries); always pass a [limit]. */
    @GET("api/libraries/{id}/recent-episodes")
    suspend fun recentEpisodes(
        @Path("id") id: String,
        @Query("limit") limit: Int,
        @Query("page") page: Int = 0,
    ): RecentEpisodesResponse

    @GET("api/items/{id}")
    suspend fun item(
        @Path("id") id: String,
        @Query("expanded") expanded: Int = 1,
    ): LibraryItemDto
}

/** Original-file download URL for one audio file (`ino` from [AudioFileDto]); honours a sub-path in [base]. */
fun audioFileUrl(base: HttpUrl, itemId: String, ino: String): HttpUrl =
    base.withTrailingSlash().newBuilder()
        .addPathSegments("api/items").addPathSegment(itemId)
        .addPathSegment("file").addPathSegment(ino).addPathSegment("download")
        .build()

fun coverUrl(base: HttpUrl, itemId: String): HttpUrl =
    base.withTrailingSlash().newBuilder()
        .addPathSegments("api/items").addPathSegment(itemId).addPathSegment("cover")
        .build()
