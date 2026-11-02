package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.auth.AbsUser
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MeApi {
    @GET("api/me")
    suspend fun me(): AbsUser

    @PATCH("api/me/progress/{itemId}")
    suspend fun updateProgress(@Path("itemId") itemId: String, @Body u: ProgressUpdate)

    @PATCH("api/me/progress/{itemId}/{episodeId}")
    suspend fun updateEpisodeProgress(
        @Path("itemId") itemId: String,
        @Path("episodeId") episodeId: String,
        @Body u: ProgressUpdate,
    )

    @POST("api/session/local-all")
    suspend fun syncLocalSessions(@Body body: LocalSessionsBody): LocalSessionsResult

    /** All bookmarks of the user (newer servers; [me] carries the same list). */
    @GET("api/me/bookmarks")
    suspend fun bookmarks(): BookmarksResponse

    /** Creates a bookmark; an existing one at the same time only gets the new title. */
    @POST("api/me/item/{itemId}/bookmark")
    suspend fun createBookmark(@Path("itemId") itemId: String, @Body body: BookmarkBody): BookmarkDto

    /** Changes the title of the bookmark at `body.time`; 404 if there is none. */
    @PATCH("api/me/item/{itemId}/bookmark")
    suspend fun updateBookmark(@Path("itemId") itemId: String, @Body body: BookmarkBody): BookmarkDto

    /** [time] from [bookmarkTimePath]; 404 if there is no bookmark at that time. */
    @DELETE("api/me/item/{itemId}/bookmark/{time}")
    suspend fun deleteBookmark(@Path("itemId") itemId: String, @Path("time") time: String)

    @GET("api/me/item/listening-sessions/{itemId}")
    suspend fun itemListeningSessions(
        @Path("itemId") itemId: String,
        @Query("itemsPerPage") itemsPerPage: Int,
        @Query("page") page: Int = 0,
    ): ListeningSessionsPage

    @GET("api/me/item/listening-sessions/{itemId}/{episodeId}")
    suspend fun episodeListeningSessions(
        @Path("itemId") itemId: String,
        @Path("episodeId") episodeId: String,
        @Query("itemsPerPage") itemsPerPage: Int,
        @Query("page") page: Int = 0,
    ): ListeningSessionsPage
}
