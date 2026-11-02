package io.wolftown.lauschig.rmab

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.absapi.AbsJson
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.external.AudibleVolume
import io.wolftown.lauschig.sync.DeviceInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.rmabDataStore by preferencesDataStore(
    name = "rmab",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** [needsReconnect]: the stored token was rejected (401); the config stays so the user sees the hint. */
data class RmabConfig(val url: String, val username: String, val needsReconnect: Boolean = false)

sealed interface RmabConnectResult {
    data object Ok : RmabConnectResult
    data object WrongLogin : RmabConnectResult
    data object PendingApproval : RmabConnectResult
    data object LocalLoginDisabled : RmabConnectResult
    data object Unreachable : RmabConnectResult
    data object InvalidUrl : RmabConnectResult

    /** Login worked but RMAB refused to create the permanent API token (limit reached, API tokens off, ...). */
    data object TokenCreationFailed : RmabConnectResult

    /** The address is `http://` and the user has not confirmed it yet; call again with `allowCleartext = true`. */
    data class NeedsCleartextConfirmation(val host: String) : RmabConnectResult
}

sealed interface RmabRequestResult {
    data object Created : RmabRequestResult
    data object AlreadyAvailable : RmabRequestResult
    data object AlreadyRequested : RmabRequestResult
    data object BeingProcessed : RmabRequestResult
    data object NotConnected : RmabRequestResult
    data object Unreachable : RmabRequestResult

    /** The Audible entry has no author; RMAB requires one, so nothing was sent. */
    data object NoAuthor : RmabRequestResult
    data class Failed(val message: String) : RmabRequestResult
}

enum class RmabStatus { WAITING, ACTIVE, DONE, FAILED }

/** Several requests for one ASIN (an old failed one plus a new one): the most advanced wins. */
private fun RmabStatus.rank() = when (this) { RmabStatus.DONE -> 3; RmabStatus.ACTIVE -> 2; RmabStatus.WAITING -> 1; RmabStatus.FAILED -> 0 }

internal fun rmabStatusOf(raw: String?): RmabStatus? = when (raw) {
    "pending", "awaiting_approval", "awaiting_release", "awaiting_search" -> RmabStatus.WAITING
    "searching", "downloading", "processing", "awaiting_import", "downloaded" -> RmabStatus.ACTIVE
    "available" -> RmabStatus.DONE
    "failed", "cancelled", "denied", "warn" -> RmabStatus.FAILED
    else -> null
}

/**
 * ReadMeABook connection: a permanent API token (never the password) and the user's own requests.
 * Never throws to callers except [CancellationException]; every failure is a result value.
 */
@Singleton
class RmabRepository(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val deviceName: () -> String,
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher, device: DeviceInfoProvider) :
        this(context.rmabDataStore, cipher, { device.get().deviceName?.takeIf(String::isNotBlank) ?: "Android" })

    val config: Flow<RmabConfig?> = store.data.map { p ->
        val url = p[URL] ?: return@map null
        RmabConfig(url, p[USERNAME].orEmpty(), p[NEEDS_RECONNECT] ?: false)
    }

    private class Session(val url: HttpUrl, val token: String, val allowCleartext: Boolean)

    private var cache: Pair<Long, Map<String, RmabStatus>>? = null

    /** Login, then trade the short-lived JWT for a permanent API token; only that token is stored. */
    suspend fun connect(url: String, username: String, password: String, allowCleartext: Boolean = false): RmabConnectResult =
        guarded(RmabConnectResult.Unreachable) {
            val base = parseUrl(url) ?: return@guarded RmabConnectResult.InvalidUrl
            if (!base.isHttps && !allowCleartext) return@guarded RmabConnectResult.NeedsCleartextConfirmation(base.host)
            val login = api(base, null, !base.isHttps).localLogin(RmabLoginBody(username.trim(), password))
            when {
                login.code() == 401 -> return@guarded RmabConnectResult.WrongLogin
                login.code() == 403 -> return@guarded RmabConnectResult.LocalLoginDisabled
                !login.isSuccessful -> return@guarded RmabConnectResult.Unreachable
            }
            val body = login.body() ?: return@guarded RmabConnectResult.Unreachable
            if (body.pendingApproval) return@guarded RmabConnectResult.PendingApproval
            val jwt = body.accessToken?.takeIf { body.success } ?: return@guarded RmabConnectResult.WrongLogin
            val tokenBody = buildJsonObject {
                put("name", "Lauschig (${deviceName()})")
                put("expiresAt", JsonNull)
            }
            val created = api(base, jwt, !base.isHttps).createApiToken(tokenBody)
            val full = created.body()?.fullToken?.takeIf { created.isSuccessful && it.isNotBlank() }
                ?: return@guarded RmabConnectResult.TokenCreationFailed
            save(base, username.trim(), full, !base.isHttps)
            RmabConnectResult.Ok
        }

    /** For RMAB accounts that only have OIDC: the user pastes a token created in RMAB; checked via `/api/auth/me`. */
    suspend fun connectWithToken(url: String, token: String, allowCleartext: Boolean = false): RmabConnectResult =
        guarded(RmabConnectResult.Unreachable) {
            val base = parseUrl(url) ?: return@guarded RmabConnectResult.InvalidUrl
            if (!base.isHttps && !allowCleartext) return@guarded RmabConnectResult.NeedsCleartextConfirmation(base.host)
            val t = token.trim()
            val me = api(base, t, !base.isHttps).me()
            when {
                me.code() == 401 || me.code() == 403 -> return@guarded RmabConnectResult.WrongLogin
                !me.isSuccessful -> return@guarded RmabConnectResult.Unreachable
            }
            save(base, me.body()?.name().orEmpty(), t, !base.isHttps)
            RmabConnectResult.Ok
        }

    suspend fun disconnect() {
        cache = null
        try { store.edit { it.clear() } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* nothing to undo */ }
    }

    suspend fun request(volume: AudibleVolume): RmabRequestResult {
        val author = volume.authors.firstOrNull { it.isNotBlank() } ?: return RmabRequestResult.NoAuthor
        return guarded(RmabRequestResult.Unreachable) {
            val s = session() ?: return@guarded RmabRequestResult.NotConnected
            val book = RmabAudiobook(
                asin = volume.asin, title = volume.title, author = author,
                narrator = volume.narrators.joinToString().takeIf { it.isNotBlank() }, coverArtUrl = volume.coverUrl,
            )
            val r = api(s.url, s.token, s.allowCleartext).createRequest(RmabCreateRequestBody(book))
            when {
                r.isSuccessful -> { cache = null; RmabRequestResult.Created }
                r.code() == 401 -> { markReconnect(); RmabRequestResult.NotConnected }
                else -> {
                    val err = r.errorBody()?.string()?.let { runCatching { AbsJson.decodeFromString<RmabErrorBody>(it) }.getOrNull() }
                    when (err?.error) {
                        "AlreadyAvailable" -> RmabRequestResult.AlreadyAvailable
                        "BeingProcessed" -> RmabRequestResult.BeingProcessed
                        "DuplicateRequest" -> RmabRequestResult.AlreadyRequested
                        else -> RmabRequestResult.Failed(err?.message ?: err?.error ?: "HTTP ${r.code()}")
                    }
                }
            }
        }
    }

    /** The user's own requests by ASIN, all pages. Cached for 60 s; empty when not connected or on any failure. */
    suspend fun statusByAsin(): Map<String, RmabStatus> {
        cache?.takeIf { nowMs() - it.first < CACHE_MS }?.let { return it.second }
        return guarded(emptyMap()) {
            val s = session() ?: return@guarded emptyMap()
            val api = api(s.url, s.token, s.allowCleartext)
            val result = HashMap<String, RmabStatus>()
            var cursor: String? = null
            var pages = 0
            do {
                val r = api.listRequests(cursor = cursor)
                if (r.code() == 401) { markReconnect(); return@guarded emptyMap() }
                val page = r.body()?.takeIf { r.isSuccessful } ?: return@guarded emptyMap()
                for (req in page.requests) {
                    val asin = req.audiobook?.audibleAsin ?: continue
                    val st = rmabStatusOf(req.status) ?: continue
                    result.merge(asin, st) { a, b -> if (a.rank() >= b.rank()) a else b }
                }
                cursor = page.nextCursor?.takeIf { it.isNotEmpty() }
            } while (cursor != null && ++pages < MAX_PAGES)
            cache = nowMs() to result
            result
        }
    }

    // --- internals ---

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // IOException (incl. unconfirmed cleartext), broken JSON, DataStore trouble: a result, never a crash.
        fallback
    }

    /** Null when not connected, the token cannot be decrypted, or the token was already rejected. */
    private suspend fun session(): Session? {
        val p = store.data.first()
        val url = p[URL]?.toHttpUrlOrNull() ?: return null
        if (p[NEEDS_RECONNECT] == true) return null
        val token = p[TOKEN]?.let(cipher::decrypt) ?: return null
        return Session(url, token, p[CLEARTEXT_KEY] == url.cleartextKey())
    }

    private suspend fun markReconnect() {
        cache = null
        store.edit { it[NEEDS_RECONNECT] = true }
    }

    private suspend fun save(base: HttpUrl, username: String, token: String, cleartext: Boolean) {
        cache = null
        store.edit {
            it.clear()
            it[URL] = base.toString()
            it[USERNAME] = username
            it[TOKEN] = cipher.encrypt(token)
            if (cleartext) it[CLEARTEXT_KEY] = base.cleartextKey()
        }
    }

    private fun parseUrl(raw: String): HttpUrl? {
        val t = raw.trim().ifEmpty { return null }
        val withScheme = if ("://" in t) t else "https://$t"
        val u = withScheme.toHttpUrlOrNull() ?: return null
        return if (u.encodedPath.endsWith("/")) u else u.newBuilder().addPathSegment("").build()
    }

    /** One client per configured URL: 10 s timeouts, Bearer only for that host, plain HTTP only if confirmed. */
    private fun api(base: HttpUrl, token: String?, allowCleartext: Boolean): RmabApi {
        val client = baseClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val sameHost = req.url.host == base.host && req.url.port == base.port
                val b = req.newBuilder().header("Accept", "application/json")
                if (token != null && sameHost) b.header("Authorization", "Bearer $token")
                chain.proceed(b.build())
            })
            // Network interceptor: also sees redirect hops.
            .addNetworkInterceptor(Interceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps && !(allowCleartext && url.cleartextKey() == base.cleartextKey())) {
                    throw IOException("Cleartext HTTP to ${url.host} was not confirmed by the user")
                }
                chain.proceed(chain.request())
            })
            .build()
        return Retrofit.Builder().baseUrl(base).client(client)
            .addConverterFactory(AbsJson.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
            .build().create(RmabApi::class.java)
    }

    private companion object {
        const val CACHE_MS = 60_000L
        const val MAX_PAGES = 50
        val URL = stringPreferencesKey("url")
        val USERNAME = stringPreferencesKey("username")
        val TOKEN = stringPreferencesKey("token")
        val CLEARTEXT_KEY = stringPreferencesKey("allow_cleartext")
        val NEEDS_RECONNECT = booleanPreferencesKey("needs_reconnect")
    }
}
