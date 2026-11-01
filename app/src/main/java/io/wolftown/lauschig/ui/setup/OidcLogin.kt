package io.wolftown.lauschig.ui.setup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.absapi.AbsJson
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.net.CleartextNotConfirmedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [OidcSignIn.start]. */
sealed interface OidcStart {
    /** Open [url] (the identity provider's login page) in a Custom Tab. */
    data class Launch(val url: String) : OidcStart

    /** HTTP 400: the server does not list `lauschig://oauth` under "Allowed Mobile Redirect URIs". */
    data object RedirectNotAllowed : OidcStart

    /** Network error, a non-redirect answer or OIDC not set up on the server. */
    data object Unreachable : OidcStart
}

/** What the deep link `lauschig://oauth?code=..&state=..` carried. */
data class OidcCallback(val code: String?, val state: String?, val error: String?) {
    companion object {
        const val SCHEME = "lauschig"
        const val HOST = "oauth"
        fun isCallback(uri: Uri?): Boolean = uri != null && uri.scheme == SCHEME && uri.host == HOST
        fun from(uri: Uri) = OidcCallback(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"))
    }
}

/** A finished SSO login: the server's answer and the address that was used for it. */
data class OidcResult(val baseUrl: HttpUrl, val response: LoginResponse)

class OidcException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    enum class Reason { NO_PENDING, STATE_MISMATCH, REJECTED, UNREACHABLE }
}

interface OidcSignIn {
    suspend fun start(baseUrl: HttpUrl): OidcStart
    suspend fun finish(callback: OidcCallback): OidcResult
}

private val Context.oidcPendingDataStore by preferencesDataStore(
    name = "oidc_pending",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * ABS OpenID sign-in for apps (PKCE, no secret). [start] asks the server for the identity provider's address, [finish]
 * trades the callback for tokens. Both steps must share the server's session cookies, and the Custom Tab may outlive
 * the process, so the pending attempt (address, PKCE verifier, state, cookies) lives in the encrypted `oidc_pending`
 * DataStore for [TTL_MS].
 */
@Singleton
class OidcLogin(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
    baseClient: OkHttpClient,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OidcSignIn {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher) :
        this(context.oidcPendingDataStore, cipher, OkHttpClient())

    // Own client: no auth, never follows redirects (the Location is for the browser), 10 s per call.
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun start(baseUrl: HttpUrl): OidcStart {
        val verifier = randomVerifier()
        val state = randomUrlSafe(24)
        val url = endpoint(baseUrl, "auth/openid").newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build()
        return try {
            call(baseUrl, url).use { r ->
                when {
                    r.code == 400 -> OidcStart.RedirectNotAllowed
                    r.isRedirect -> {
                        val location = r.header("Location")
                        if (location.isNullOrBlank()) OidcStart.Unreachable
                        else {
                            savePending(baseUrl, verifier, state, r.headers("Set-Cookie"))
                            OidcStart.Launch(location)
                        }
                    }
                    else -> OidcStart.Unreachable
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            OidcStart.Unreachable
        }
    }

    override suspend fun finish(callback: OidcCallback): OidcResult {
        val p = loadPending() ?: throw OidcException(OidcException.Reason.NO_PENDING)
        // A link that does not belong to the attempt we started is dropped; the pending attempt stays for the real one.
        if (callback.state.isNullOrEmpty() || callback.state != p.state) throw OidcException(OidcException.Reason.STATE_MISMATCH)
        val code = callback.code?.takeIf { it.isNotEmpty() && callback.error == null } ?: throw OidcException(OidcException.Reason.REJECTED)
        val url = endpoint(p.baseUrl, "auth/openid/callback").newBuilder()
            .addQueryParameter("state", p.state)
            .addQueryParameter("code", code)
            .addQueryParameter("code_verifier", p.verifier)
            .build()
        val response = try {
            call(p.baseUrl, url, p.cookies).use { r ->
                if (!r.isSuccessful) throw OidcException(OidcException.Reason.REJECTED)
                AbsJson.decodeFromString(LoginResponse.serializer(), r.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OidcException) {
            throw e
        } catch (e: IOException) {
            throw OidcException(OidcException.Reason.UNREACHABLE, e)
        } catch (e: Exception) {
            throw OidcException(OidcException.Reason.REJECTED, e)
        }
        store.edit { it.clear() } // only after the server accepted the code
        return OidcResult(p.baseUrl, response)
    }

    private class Pending(val baseUrl: HttpUrl, val verifier: String, val state: String, val cookies: List<String>)

    private suspend fun savePending(baseUrl: HttpUrl, verifier: String, state: String, setCookies: List<String>) {
        store.edit {
            it[BASE_URL] = baseUrl.toString()
            it[VERIFIER] = cipher.encrypt(verifier)
            it[STATE] = state
            it[COOKIES] = cipher.encrypt(AbsJson.encodeToString(ListSerializer(String.serializer()), setCookies))
            it[STARTED_AT] = nowMs()
        }
    }

    private suspend fun loadPending(): Pending? {
        val p = store.data.first()
        val started = p[STARTED_AT] ?: return null
        if (nowMs() - started !in 0..TTL_MS) return null
        val base = p[BASE_URL]?.toHttpUrlOrNull() ?: return null
        val verifier = p[VERIFIER]?.let(cipher::decrypt) ?: return null
        val cookies = p[COOKIES]?.let(cipher::decrypt)?.let { AbsJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: return null
        return Pending(base, verifier, p[STATE] ?: return null, cookies)
    }

    /** One GET. Plain HTTP only to the very address the user confirmed; stored Set-Cookie lines act as the cookie jar. */
    private suspend fun call(baseUrl: HttpUrl, url: HttpUrl, setCookies: List<String> = emptyList()): okhttp3.Response {
        val guarded = client.newBuilder()
            .cookieJar(StoredCookieJar(url, setCookies, nowMs))
            .addNetworkInterceptor { chain ->
                val u = chain.request().url
                if (!u.isHttps && u.cleartextKey() != baseUrl.cleartextKey()) throw CleartextNotConfirmedException(u.host)
                chain.proceed(chain.request())
            }.build()
        return withContext(Dispatchers.IO) { guarded.newCall(Request.Builder().url(url).get().build()).execute() }
    }

    /** Replays the cookies the server set in [start]; what a response sets is read from the headers by the caller. */
    private class StoredCookieJar(origin: HttpUrl, setCookies: List<String>, private val nowMs: () -> Long) : CookieJar {
        private val cookies = setCookies.mapNotNull { Cookie.parse(origin, it) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.expiresAt > nowMs() && it.matches(url) }
    }

    companion object {
        const val CLIENT_ID = "Lauschig"
        const val REDIRECT_URI = "lauschig://oauth"
        const val TTL_MS = 10 * 60_000L
        private val BASE_URL = stringPreferencesKey("base_url")
        private val VERIFIER = stringPreferencesKey("verifier")
        private val STATE = stringPreferencesKey("state")
        private val COOKIES = stringPreferencesKey("cookies")
        private val STARTED_AT = longPreferencesKey("started_at")
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private val random = SecureRandom()

        /** RFC 7636 verifier: 64 characters from the unreserved set. */
        fun randomVerifier(): String = buildString { repeat(64) { append(UNRESERVED[random.nextInt(UNRESERVED.length)]) } }

        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun randomUrlSafe(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        /** Relative to [base] like `audioFileUrl`, so a server under a sub-path (`/audiobookshelf`) works. */
        fun endpoint(base: HttpUrl, path: String): HttpUrl =
            (if (base.encodedPath.endsWith("/")) base else base.newBuilder().addPathSegment("").build())
                .newBuilder().addPathSegments(path).build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OidcModule {
    @Binds abstract fun oidc(impl: OidcLogin): OidcSignIn
}

/**
 * Hands the deep link from [io.wolftown.lauschig.MainActivity] to the active SetupViewModel. An entry nobody picks up
 * within [TTL_MS] (user already signed in, no setup screen) is dropped, so a stray link never triggers anything later.
 */
@Singleton
class OidcCallbackBus @Inject constructor() {
    class Entry(val callback: OidcCallback, val atMs: Long)

    private val _pending = MutableStateFlow<Entry?>(null)
    val pending: StateFlow<Entry?> = _pending

    fun post(callback: OidcCallback, atMs: Long = System.currentTimeMillis()) { _pending.value = Entry(callback, atMs) }

    /** Takes the entry if it is fresh; stale ones are discarded. */
    fun take(nowMs: Long = System.currentTimeMillis()): OidcCallback? {
        val e = _pending.value ?: return null
        _pending.value = null
        return e.callback.takeIf { nowMs - e.atMs in 0..TTL_MS }
    }

    companion object { const val TTL_MS = 30_000L }
}
