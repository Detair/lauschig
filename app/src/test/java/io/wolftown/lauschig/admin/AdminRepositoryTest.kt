package io.wolftown.lauschig.admin

import io.wolftown.lauschig.absapi.AbsClientFactory
import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Against a MockWebServer standing in for ABS 2.37.1; never a real server. */
class AdminRepositoryTest {
    private lateinit var server: MockWebServer
    private val sid = "0123456789abcdef"
    private val account = MutableStateFlow<Account?>(null)
    private val refreshed = mutableListOf<String>()
    private var librarySyncs = 0
    private lateinit var repo: AdminRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val retrofit = AbsClientFactory.retrofit(server.url("/"), OkHttpClient())
        account.value = Account(sid, "root", "root", true, ServerAddresses(null, server.url("/")), emptySet())
        repo = AdminRepository(
            admin = flowOf(retrofit.create(AdminApi::class.java)),
            library = flowOf(retrofit.create(LibraryApi::class.java)),
            account = account,
            itemLibraryId = { _, id -> if (id == "li1") "lib1" else "lib2" },
            loadDetails = { _, id -> refreshed += id },
            syncLibrarySoon = { librarySyncs++ },
        )
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun scanLibrary_plainOk_schedulesALibrarySync() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("OK"))
        assertEquals(AdminResult.Ok(Unit), repo.scanLibrary("lib1"))
        assertEquals("/api/libraries/lib1/scan", server.takeRequest().path)
        assertEquals(1, librarySyncs)
    }

    @Test fun forbidden_fromTheServer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AdminResult.Forbidden, repo.scanLibrary("lib1"))
        assertEquals(0, librarySyncs)
    }

    @Test fun nonAdmin_neverAsksTheServer() = runBlocking {
        account.value = account.value!!.copy(isAdmin = false)
        assertEquals(AdminResult.Forbidden, repo.scanLibrary("lib1"))
        assertEquals(AdminResult.Forbidden, repo.onlineUsers())
        assertEquals(0, server.requestCount)
    }

    @Test fun offline_isOffline() = runBlocking {
        val offline = AdminRepository(flowOf(null), flowOf(null), account, { _, _ -> null }, { _, _ -> }, {})
        assertEquals(AdminResult.Offline, offline.scanItem("li1"))
    }

    @Test fun scanItem_refreshesTheTitle() = runBlocking {
        server.enqueue(json("""{"result":"UPDATED"}"""))
        assertEquals(AdminResult.Ok("UPDATED"), repo.scanItem("li1"))
        assertEquals(listOf("li1"), refreshed)
    }

    @Test fun match_usesTheLibrariesProvider_andRefreshes() = runBlocking {
        server.enqueue(json("""{"libraries":[{"id":"lib1","name":"Hörbücher","mediaType":"book","provider":"audible.de"}]}"""))
        server.enqueue(json("""{"updated":true,"libraryItem":{}}"""))
        assertEquals(AdminResult.Ok(MatchOutcome.Matched(updated = true)), repo.matchItem("li1"))
        assertEquals("/api/libraries", server.takeRequest().path)
        val match = server.takeRequest()
        assertEquals("/api/items/li1/match", match.path)
        assertEquals("""{"provider":"audible.de"}""", match.body.readUtf8())
        assertEquals(listOf("li1"), refreshed)
    }

    @Test fun match_withoutProvider_doesNotMatch() = runBlocking {
        server.enqueue(json("""{"libraries":[{"id":"lib2","name":"Alt","mediaType":"book"}]}"""))
        assertEquals(AdminResult.Ok(MatchOutcome.NoProvider), repo.matchItem("li2"))
        assertEquals(1, server.requestCount)
        assertTrue(refreshed.isEmpty())
    }

    @Test fun match_warning_isNoMatch() = runBlocking {
        server.enqueue(json("""{"libraries":[{"id":"lib1","name":"Hörbücher","mediaType":"book","provider":"audible.de"}]}"""))
        server.enqueue(json("""{"warning":"No audible.de match found"}"""))
        assertEquals(AdminResult.Ok(MatchOutcome.NoMatch("No audible.de match found")), repo.matchItem("li1"))
        assertTrue(refreshed.isEmpty())
    }

    @Test fun serverError_isAFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AdminResult.Failed("HTTP 500"), repo.scanItem("li1"))
    }
}
