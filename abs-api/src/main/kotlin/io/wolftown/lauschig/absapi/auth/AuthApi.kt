package io.wolftown.lauschig.absapi.auth

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface AuthApi {
    @POST("login")
    @Headers("x-return-tokens: true")
    suspend fun login(@Body r: LoginRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Header("x-refresh-token") refreshToken: String): LoginResponse

    @GET("ping")
    suspend fun ping(): PingResponse
}
