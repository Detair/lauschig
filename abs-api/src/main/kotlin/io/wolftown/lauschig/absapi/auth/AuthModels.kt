package io.wolftown.lauschig.absapi.auth

import io.wolftown.lauschig.absapi.LenientMediaProgressListSerializer
import io.wolftown.lauschig.absapi.me.BookmarkDto
import io.wolftown.lauschig.absapi.me.LenientBookmarkListSerializer
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
    /**
     * `toOldJSONForBrowser` (login, `GET /api/me`); see [BookmarkDto]. Null when the field is absent, so a missing
     * field is never mistaken for "every bookmark was deleted".
     */
    @Serializable(with = LenientBookmarkListSerializer::class)
    val bookmarks: List<BookmarkDto>? = null,
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

/** `GET /status` (no login needed). */
@Serializable
data class StatusResponse(
    val serverVersion: String? = null,
    /** "local" and/or "openid". */
    val authMethods: List<String> = emptyList(),
    val authFormData: AuthFormData? = null,
)

@Serializable
data class AuthFormData(
    val authOpenIDButtonText: String? = null,
    val authOpenIDAutoLaunch: Boolean = false,
    val authLoginCustomMessage: String? = null,
)
