package io.wolftown.lauschig.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.net.TwoUsersServer
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_A
import io.wolftown.lauschig.net.TwoUsersServer.Companion.BEARER_B
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Review N1: the stream's token is the title's owner's, never the signed-in account's. The whole way a stream request takes:
 * [MediaItemFactory] builds the URI, Media3's own data source opens it, [StreamCalls] picks the client, the server sees it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamCallsTest {
    private val web = TwoUsersServer()
    private val a = web.a.serverId
    private val b = web.b.serverId
    private val store: FakeAccountStore = web.store()
    private val api: ApiProvider = web.api(store)
    private var online: HttpUrl? = web.server.url("/")
    private val calls = StreamCalls({ online }, api)

    @After fun tearDown() = web.shutdown()

    /** The URI of [server]'s title, as the player gets it. */
    private fun uriOf(server: String, id: String, base: HttpUrl = web.server.url("/")): Uri {
        val row = ItemWithDetails(ItemEntity(server, id, "lib", "Titel"), emptyList(), listOf(AudioFileEntity(server, id, 1, "ino", 100.0, 1, "mp3")), null, null)
        return MediaItemFactory({ true }) { _, _ -> null }.build(row, base).single().localConfiguration!!.uri
    }

    /** What the player's loader does with [uri]. */
    private fun open(uri: Uri, factory: Call.Factory = calls) {
        val source = OkHttpDataSource.Factory(factory).createDataSource()
        try {
            source.open(DataSpec(uri))
        } finally {
            source.close()
        }
    }

    @Test fun theOwnerSurvivesMedia3sDataSource_andNeverGoesOnTheWire() {
        var seenByTheFactory: String? = null
        open(uriOf(a, "a-book"), Call.Factory { request: Request ->
            seenByTheFactory = request.url.fragment
            calls.newCall(request)
        })
        assertEquals(a, seenByTheFactory)
        val request = web.seen.single()
        assertEquals("/api/items/a-book/file/ino/download", request.path)
        assertEquals(BEARER_A, request.auth)
    }

    @Test fun aTitleOfAWithBActive_isNotSent_notWithBsToken() {
        runBlocking { store.activate(b) }
        val failed = runCatching { open(uriOf(a, "a-book")) }.exceptionOrNull()
        assertTrue("refused: $failed", failed is IOException)
        assertEquals(emptyList<TwoUsersServer.Seen>(), web.seen.toList())
    }

    @Test fun aTitleOfB_whenBIsActive_goesWithBsToken() {
        runBlocking { store.activate(b) }
        open(uriOf(b, "b-book"))
        assertEquals(listOf<String?>(BEARER_B), web.tokens())
    }

    /** Two servers: a queue built just after the switch can point A's title at B's address. It still is not B's request. */
    @Test fun aTitleOfABuiltOnBsServer_isNotSent() {
        val second = TwoUsersServer()
        try {
            store.aside.value = listOf(FakeAccountStore.Aside(second.b, TwoUsersServer.TOKEN_B, "b-refresh", false))
            runBlocking { store.activate(b) }
            online = second.server.url("/")
            assertTrue(runCatching { open(uriOf(a, "a-book", second.server.url("/"))) }.exceptionOrNull() is IOException)
            assertEquals(emptyList<TwoUsersServer.Seen>(), second.seen.toList())
        } finally {
            second.shutdown()
        }
    }

    @Test fun aRequestWithoutAnOwner_goesWithoutAToken() {
        runCatching { open(Uri.parse(web.server.url("/api/items/a-book/file/ino/download").toString())) }
        assertEquals(listOf<String?>(null), web.tokens())
    }

    @Test fun anAddressThatIsNotTheOneOnline_goesWithoutAToken() {
        online = null
        runCatching { open(uriOf(a, "a-book")) }
        assertEquals(listOf<String?>(null), web.tokens())
    }

    /** The token follows the account's confirmed addresses as before, also when the state flipped to offline and back. */
    @Test fun whileTheOwnerIsSignedIn_nothingChanges_onItsSecondAddressEither() {
        open(uriOf(a, "a-book"))
        open(uriOf(a, "a-book"))
        assertEquals(listOf<String?>(BEARER_A), web.tokens())
        assertEquals(2, web.seen.size)
    }
}
