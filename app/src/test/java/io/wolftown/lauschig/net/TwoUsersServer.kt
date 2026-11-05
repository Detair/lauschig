package io.wolftown.lauschig.net

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.connection.accountConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One ABS server with two users, told apart only by the access token, each with a title of their own ("a-book", "b-book",
 * in "a-lib", "b-lib"): what a request gets shows whose token it carried, and [seen] keeps the token of every request.
 */
class TwoUsersServer {
    data class Seen(val method: String, val path: String, val auth: String?)

    val server = MockWebServer().also { it.start() }
    val seen = CopyOnWriteArrayList<Seen>()
    /** Called for every request before it is answered (a test switches accounts in the middle of a run with it). */
    @Volatile var onRequest: (RecordedRequest) -> Unit = {}
    /** Seconds of "a-book"/"b-book" on the server. */
    @Volatile var position = 10.0

    /** The two accounts: same server (the family case), each with its own server id. */
    val a = account("aaaaaaaaaaaaaaaa", "u-a", "fabian")
    val b = account("bbbbbbbbbbbbbbbb", "u-b", "matthias")

    private fun account(serverId: String, userId: String, name: String) = Account(
        serverId, userId, name, true, ServerAddresses(server.url("/"), null), setOf("http://${server.hostName}:${server.port}"),
    )

    /** The store as the app has it on a family tablet: A active, B set aside. */
    fun store() = FakeAccountStore().apply {
        acc.value = a
        access = TOKEN_A
        refresh = "a-refresh"
        aside.value = listOf(FakeAccountStore.Aside(b, TOKEN_B, "b-refresh", false))
    }

    /** The provider as the app builds it: every server starts with a reset, then its own probe answers. */
    fun api(store: FakeAccountStore, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) = ApiProvider(
        accountConnection(store.account) { addresses ->
            flow {
                emit(ConnectionState.Online(addresses.local!!, ConnectionState.Via.LOCAL))
                awaitCancellation()
            }
        },
        OkHttpClient(), store, scope,
    )

    /** What collecting the state keeps alive in the app (a screen, the service): the state stays current. */
    suspend fun keepOnline(api: ApiProvider, scope: CoroutineScope) {
        scope.launch { api.connection.collect {} }
        withTimeout(5_000) { api.connection.filterIsInstance<ConnectionState.Online>().first() }
    }

    fun tokens() = seen.map { it.auth }.distinct()

    /** Every request but the unauthenticated ones (ping, logout). */
    fun authed() = seen.filter { it.path != "/ping" && it.path != "/logout" }

    fun shutdown() = server.shutdown()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val auth = request.getHeader("Authorization")
                seen += Seen(request.method.orEmpty(), path, auth)
                onRequest(request)
                if (path == "/ping") return json("""{"success":true}""")
                if (path == "/logout") return json("""{"redirect_url":null}""")
                val who = when (auth) {
                    "Bearer $TOKEN_A" -> "a"
                    "Bearer $TOKEN_B" -> "b"
                    else -> return MockResponse().setResponseCode(401)
                }
                return answer(request.method.orEmpty(), path.substringBefore('?'), who)
            }
        }
    }

    private fun answer(method: String, path: String, who: String): MockResponse = when {
        path == "/api/me" -> json(
            """{"id":"u-$who","username":"$who","type":"user","bookmarks":[],"mediaProgress":[
                {"libraryItemId":"$who-book","currentTime":$position,"duration":100,"progress":0.1,"isFinished":false,"lastUpdate":1000}]}""",
        )
        method == "PATCH" && path.startsWith("/api/me/progress/") -> json("{}")
        path == "/api/session/local-all" -> json("""{"results":[]}""")
        path == "/api/me/bookmarks" -> json("""{"bookmarks":[{"libraryItemId":"$who-book","time":5,"title":"$who"}]}""")
        method == "POST" && path.endsWith("/bookmark") -> json("""{"libraryItemId":"$who-book","time":5,"title":"$who"}""")
        path == "/api/libraries" -> json("""{"libraries":[{"id":"$who-lib","name":"$who","mediaType":"book"}]}""")
        path.endsWith("/items") -> json("""{"results":[${item(who)}],"total":1,"limit":200,"page":0}""")
        path.endsWith("/collections") -> json("""{"results":[{"id":"$who-col","name":"$who","libraryId":"$who-lib","books":[{"id":"$who-book"}],"lastUpdate":1}],"total":1}""")
        path.endsWith("/authors") -> json("""{"authors":[{"id":"$who-author","name":"$who"}]}""")
        path == "/api/playlists" -> json("""{"playlists":[]}""")
        path == "/api/me/listening-sessions" -> json(
            """{"total":1,"numPages":1,"page":0,"itemsPerPage":250,"sessions":[
                {"id":"$who-session","libraryItemId":"$who-book","displayTitle":"$who","startedAt":1000,"updatedAt":2000,"timeListening":60}]}""",
        )
        path.startsWith("/api/me/item/listening-sessions/") -> json(
            """{"total":1,"numPages":1,"page":0,"itemsPerPage":100,"sessions":[
                {"id":"$who-history","libraryItemId":"$who-book","startedAt":1000,"updatedAt":2000}]}""",
        )
        path.contains("/file/") -> MockResponse().setBody(okio.Buffer().write(ByteArray(FILE_BYTES)))
        path.endsWith("/cover") -> MockResponse().setResponseCode(404)
        path.startsWith("/api/items/") -> json(item(who))
        else -> MockResponse().setResponseCode(404)
    }

    private fun item(who: String) = """{"id":"$who-book","libraryId":"$who-lib","mediaType":"book","updatedAt":1,
        "media":{"metadata":{"title":"Title $who"},"duration":100,
        "audioFiles":[{"index":1,"ino":"$who-ino","duration":100,"metadata":{"filename":"f.mp3","ext":".mp3","size":$FILE_BYTES}}]}}"""

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        const val TOKEN_A = "a-access"
        const val TOKEN_B = "b-access"
        const val BEARER_A = "Bearer $TOKEN_A"
        const val BEARER_B = "Bearer $TOKEN_B"
        const val FILE_BYTES = 2_048
    }
}
