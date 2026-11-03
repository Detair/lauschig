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

    /**
     * Ends this device's session on the server: ABS (2.26+, `Auth.js`) invalidates [refreshToken] (an access token
     * still runs out on its own). Unauthenticated; the server root, not `/api`. Answers 200 with or without a token.
     */
    @POST("logout")
    suspend fun logout(@Header("x-refresh-token") refreshToken: String): LogoutResponse

    /** Unauthenticated; tells which sign-in methods the server offers (`openid` only when OIDC is configured). */
    @GET("status")
    suspend fun status(): StatusResponse

    @GET("ping")
    suspend fun ping(): PingResponse
}
