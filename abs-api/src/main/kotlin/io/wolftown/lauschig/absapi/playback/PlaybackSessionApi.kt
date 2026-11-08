package io.wolftown.lauschig.absapi.playback

import io.wolftown.lauschig.absapi.FlexibleDoubleSerializer
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.withTrailingSlash
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * A playback session on the server: what a Cast receiver plays from. The session's tracks answer without a token at
 * [sessionTrackUrl] until the session is closed, so the receiver holds an address that ends with the session and never a
 * long-lived token. The app's own playback does not use these sessions (it syncs local sessions, `MeApi.syncLocalSessions`).
 */
interface PlaybackSessionApi {
    /** Opens a session of a book. The server closes any session this user has open on the same device first. */
    @POST("api/items/{id}/play")
    suspend fun start(@Path("id") itemId: String, @Body body: PlaybackSessionRequest): PlaybackSessionDto

    @POST("api/items/{id}/play/{episodeId}")
    suspend fun startEpisode(@Path("id") itemId: String, @Path("episodeId") episodeId: String, @Body body: PlaybackSessionRequest): PlaybackSessionDto

    /**
     * Ends the session; its track addresses answer 404 from then on. Without sync data the server stores nothing: a
     * session that never had listening time leaves no trace in the statistics. A session that is gone answers 404.
     */
    @POST("api/session/{id}/close")
    suspend fun close(@Path("id") sessionId: String): Response<Unit>
}

/** All fields are sent: the JSON leaves out defaults, and the server would pick a transcode without [forceDirectPlay]. */
@Serializable
data class PlaybackSessionRequest(
    val deviceInfo: DeviceInfoDto,
    val forceDirectPlay: Boolean,
    val mediaPlayer: String,
)

@Serializable
data class PlaybackSessionDto(
    val id: String,
    val audioTracks: List<PlaybackTrackDto> = emptyList(),
)

/** One file of a session. [index] starts at 1 and names the track in [sessionTrackUrl]; an old podcast episode may have none. */
@Serializable
data class PlaybackTrackDto(
    val index: Int? = null,
    @Serializable(with = FlexibleDoubleSerializer::class) val startOffset: Double = 0.0,
    @Serializable(with = FlexibleDoubleSerializer::class) val duration: Double = 0.0,
    val mimeType: String? = null,
)

/** The public address of one track of a session (`/public/session/<id>/track/<index>`, ABS ≥ 2.22); honours a sub-path in [base]. */
fun sessionTrackUrl(base: HttpUrl, sessionId: String, index: Int): HttpUrl =
    base.withTrailingSlash().newBuilder()
        .addPathSegments("public/session").addPathSegment(sessionId)
        .addPathSegment("track").addPathSegment(index.toString())
        .build()
