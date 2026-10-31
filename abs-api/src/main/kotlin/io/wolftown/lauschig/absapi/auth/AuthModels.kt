package io.wolftown.lauschig.absapi.auth

import io.wolftown.lauschig.absapi.LenientMediaProgressListSerializer
import io.wolftown.lauschig.absapi.me.MediaProgressDto
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Response of `POST /login` and `POST /auth/refresh` (server `Auth.getUserLoginResponsePayload`). */
@Serializable
data class LoginResponse(
    val user: AbsUser,
    val userDefaultLibraryId: String? = null,
    val serverSettings: ServerSettings? = null,
)

/**
 * `User.toOldJSONForBrowser()` plus the tokens attached in `Auth.handleLoginSuccess`.
 * `refreshToken` is only present when the request carried `x-return-tokens: true` / `x-refresh-token`.
 */
@Serializable
data class AbsUser(
    val id: String,
    val username: String,
    val type: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    @Serializable(with = LenientMediaProgressListSerializer::class)
    val mediaProgress: List<MediaProgressDto> = emptyList(),
)

/** Subset of `ServerSettings.toJSONForBrowser()`. */
@Serializable
data class ServerSettings(
    val id: String? = null,
    val version: String? = null,
    val buildNumber: Int? = null,
    val timeZone: String? = null,
)

@Serializable
data class PingResponse(val success: Boolean)
