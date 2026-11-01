package io.wolftown.lauschig.absapi.me

import io.wolftown.lauschig.absapi.auth.AbsUser
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface MeApi {
    @GET("api/me")
    suspend fun me(): AbsUser

    @PATCH("api/me/progress/{itemId}")
    suspend fun updateProgress(@Path("itemId") itemId: String, @Body u: ProgressUpdate)

    @POST("api/session/local-all")
    suspend fun syncLocalSessions(@Body body: LocalSessionsBody): LocalSessionsResult
}
