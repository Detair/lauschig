package io.wolftown.lauschig.absapi.admin

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The few admin calls of Lauschig's "Server-Verwaltung" (ABS 2.37.1). Scans and online users need an admin (403
 * otherwise), the match needs the "update" permission. Non-2xx answers throw `HttpException`.
 */
interface AdminApi {
    /**
     * Starts a library scan. ABS answers `200 OK` with the plain text "OK" before the scan runs (not JSON): the
     * `Unit` return type makes Retrofit skip the body.
     */
    @POST("api/libraries/{id}/scan")
    suspend fun scanLibrary(@Path("id") libraryId: String)

    /** Re-scans one item's files and waits for it; `{ "result": "UPDATED" | "UPTODATE" | "REMOVED" | … }`. */
    @POST("api/items/{id}/scan")
    suspend fun scanItem(@Path("id") itemId: String): ItemScanResult

    /** Quick match with the library's metadata provider; title and author come from the item on the server. */
    @POST("api/items/{id}/match")
    suspend fun matchItem(@Path("id") itemId: String, @Body body: MatchRequest): MatchResult

    /** Users with a socket connection (web, official app) and the open playback sessions. */
    @GET("api/users/online")
    suspend fun onlineUsers(): OnlineUsers
}
