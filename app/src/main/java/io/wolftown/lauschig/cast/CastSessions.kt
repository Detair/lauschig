package io.wolftown.lauschig.cast

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.playback.PlaybackSessionApi
import io.wolftown.lauschig.absapi.playback.PlaybackSessionRequest
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.sync.DeviceInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/** One file of a session; [index] names it in the track address. */
data class ReceiverTrack(val index: Int, val durationSec: Double, val mimeType: String)

/** A session open on the server for a receiver. [sessionId] is a capability: never in a log. */
class OpenCastSession(val serverId: String, val key: String, val sessionId: String, val tracks: List<ReceiverTrack>)

/** The server cannot be asked right now (offline, or [OpenCastSession.serverId] is not the signed-in account). */
class CastServerOffline : Exception("no server to open a session on")

/** The server side of a cast. Main-safe. */
interface CastSessionSource {
    /** Opens a session for a title; throws [CastServerOffline] or the request's own error. */
    suspend fun open(serverId: String, key: String): OpenCastSession

    /** Closes it in the background, also if the caller goes away. */
    fun close(session: OpenCastSession)

    /** Where a receiver can load this server from, best first ([receiverAddresses]). */
    suspend fun addresses(serverId: String): List<HttpUrl>
}

/** The sessions that are open on a server, across a process death (so the next start can close them). */
interface CastSessionStore {
    suspend fun add(serverId: String, sessionId: String)
    suspend fun remove(serverId: String, sessionId: String)
    suspend fun all(): List<Pair<String, String>>
}

private val Context.castSessionsDataStore by preferencesDataStore(name = "cast_sessions")

class DataStoreCastSessionStore @Inject constructor(@ApplicationContext private val context: Context) : CastSessionStore {
    private val key = stringSetPreferencesKey("open")

    override suspend fun add(serverId: String, sessionId: String) {
        context.castSessionsDataStore.edit { it[key] = it[key].orEmpty() + "$serverId|$sessionId" }
    }

    override suspend fun remove(serverId: String, sessionId: String) {
        context.castSessionsDataStore.edit { it[key] = it[key].orEmpty() - "$serverId|$sessionId" }
    }

    override suspend fun all(): List<Pair<String, String>> =
        context.castSessionsDataStore.data.map { it[key].orEmpty() }.first().mapNotNull { entry ->
            val (serverId, sessionId) = entry.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            serverId to sessionId
        }
}

/**
 * The sessions opened for a cast. A session ends when casting ends or the title changes; what a process death left open
 * the next start closes ([closeLeftovers]). The rest the server does itself: it closes what a device opens anew, and every
 * night (00:30) what was not updated for 36 hours (`closeStaleOpenSessions`, ABS 2.26 to 2.37).
 *
 * **Statistics:** these sessions never get listening time from here, and the server stores no session without it. What a
 * cast counts goes the way every local listen does: [io.wolftown.lauschig.playback.ProgressTracker] writes one local
 * session and the sync sends it. So a cast counts once.
 */
@Singleton
class CastSessions(
    /** The account's API while it is the signed-in one and online; null otherwise. */
    private val api: (serverId: String) -> Flow<PlaybackSessionApi?>,
    private val deviceInfo: () -> DeviceInfoDto,
    private val store: CastSessionStore,
    private val accounts: AccountStore,
    /** Outlives the service: a close still goes out after it stopped. */
    private val scope: CoroutineScope,
    private val waitMs: Long = API_WAIT_MS,
) : CastSessionSource {
    @Inject constructor(api: ApiProvider, device: DeviceInfoProvider, store: DataStoreCastSessionStore, accounts: AccountStore) : this(
        api::playbackSessionApi, device::get, store, accounts, CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private suspend fun client(serverId: String): PlaybackSessionApi? = withTimeoutOrNull(waitMs) { api(serverId).filterNotNull().firstOrNull() }

    override suspend fun open(serverId: String, key: String): OpenCastSession {
        val client = client(serverId) ?: throw CastServerOffline()
        val parsed = PlayableKey.parse(key)
        val body = PlaybackSessionRequest(deviceInfo(), forceDirectPlay = true, mediaPlayer = MEDIA_PLAYER)
        val episodeId = parsed.episodeId
        val dto = if (episodeId != null) client.startEpisode(parsed.libraryItemId, episodeId, body) else client.start(parsed.libraryItemId, body)
        store.add(serverId, dto.id)
        val tracks = dto.audioTracks.mapIndexed { i, t -> ReceiverTrack(t.index ?: (i + 1), t.duration, t.mimeType ?: DEFAULT_MIME) }.sortedBy { it.index }
        return OpenCastSession(serverId, key, dto.id, tracks)
    }

    override fun close(session: OpenCastSession) {
        scope.launch { closeNow(session.serverId, session.sessionId) }
    }

    override suspend fun addresses(serverId: String): List<HttpUrl> {
        val account = accounts.account.first()?.takeIf { it.serverId == serverId } ?: return emptyList()
        return receiverAddresses(account.addresses)
    }

    /**
     * Before a sign-out or a profile switch takes the token away: closes what is open for [serverId] and returns once that
     * is done or could not be done.
     */
    suspend fun closeOpen(serverId: String) {
        store.all().filter { it.first == serverId }.forEach { (sid, id) -> closeNow(sid, id) }
    }

    /**
     * At the start of the app, with what the last process left open. The active account's go now; a saved profile's wait for
     * its return. An account that is gone has no token any more, so its sessions are left to the server.
     */
    suspend fun closeLeftovers() {
        val open = store.all()
        if (open.isEmpty()) return
        val active = accounts.account.first()?.serverId
        val saved = accounts.saved.first().map { it.serverId }
        open.forEach { (serverId, sessionId) ->
            when (serverId) {
                active -> closeNow(serverId, sessionId)
                in saved -> Unit
                else -> store.remove(serverId, sessionId)
            }
        }
    }

    /** True once the session is known to be closed. A server that says it is gone counts: the address is dead either way. */
    private suspend fun closeNow(serverId: String, sessionId: String): Boolean {
        val client = client(serverId) ?: return false
        val closed = try {
            val response = client.close(sessionId)
            response.isSuccessful || response.code() == HTTP_NOT_FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "closing a cast session failed", e)
            false
        }
        if (closed) store.remove(serverId, sessionId)
        return closed
    }

    private companion object {
        const val TAG = "CastSessions"
        /** How the session shows up in the server's admin view. */
        const val MEDIA_PLAYER = "chromecast"
        const val DEFAULT_MIME = "audio/mpeg"
        const val HTTP_NOT_FOUND = 404
        /** Long enough for the first connection check of a cold start, short enough for a person waiting at the button. */
        const val API_WAIT_MS = 8_000L
    }
}
