package io.wolftown.lauschig.rmab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** ReadMeABook endpoints (research doc section 3). Bearer auth only; the repository adds the header. */
interface RmabApi {
    @GET("api/auth/providers")
    suspend fun providers(): Response<RmabProviders>

    @POST("api/auth/local/login")
    suspend fun localLogin(@Body body: RmabLoginBody): Response<RmabLoginResponse>

    /** Body is a [JsonObject] because `expiresAt` must be an explicit `null` (no expiry). */
    @POST("api/user/api-tokens")
    suspend fun createApiToken(@Body body: JsonObject): Response<RmabApiTokenResponse>

    @GET("api/auth/me")
    suspend fun me(): Response<RmabMeResponse>

    @POST("api/requests")
    suspend fun createRequest(@Body body: RmabCreateRequestBody): Response<RmabCreatedResponse>

    @GET("api/requests")
    suspend fun listRequests(
        @Query("myOnly") myOnly: Boolean = true,
        @Query("take") take: Int = 100,
        @Query("cursor") cursor: String? = null,
    ): Response<RmabRequestList>
}

@Serializable data class RmabProviders(val providers: List<String> = emptyList())

@Serializable data class RmabLoginBody(val username: String, val password: String)

@Serializable
data class RmabLoginResponse(
    val success: Boolean = false,
    val accessToken: String? = null,
    val pendingApproval: Boolean = false,
    val message: String? = null,
)

@Serializable data class RmabApiTokenResponse(val fullToken: String? = null)

@Serializable data class RmabMeUser(val username: String? = null, val plexUsername: String? = null)

/** Tolerates both `{user:{username}}` and a flat `{username}`. */
@Serializable
data class RmabMeResponse(val user: RmabMeUser? = null, val username: String? = null) {
    fun name(): String? = (user?.username ?: user?.plexUsername ?: username)?.takeIf { it.isNotBlank() }
}

@Serializable
data class RmabCreateRequestBody(val audiobook: RmabAudiobook)

@Serializable
data class RmabAudiobook(
    val asin: String,
    val title: String,
    val author: String,
    val narrator: String? = null,
    val coverArtUrl: String? = null,
)

@Serializable data class RmabCreatedResponse(val success: Boolean = false)

@Serializable data class RmabErrorBody(val error: String? = null, val message: String? = null)

@Serializable
data class RmabRequestList(val requests: List<RmabRequestDto> = emptyList(), val nextCursor: String? = null)

@Serializable
data class RmabRequestDto(val id: String? = null, val status: String? = null, val audiobook: RmabRequestBook? = null)

@Serializable data class RmabRequestBook(val audibleAsin: String? = null, val title: String? = null)
