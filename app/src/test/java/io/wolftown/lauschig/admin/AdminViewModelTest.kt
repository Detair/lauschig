package io.wolftown.lauschig.admin

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.absapi.admin.AdminApi
import io.wolftown.lauschig.absapi.admin.ItemScanResult
import io.wolftown.lauschig.absapi.admin.MatchRequest
import io.wolftown.lauschig.absapi.admin.MatchResult
import io.wolftown.lauschig.absapi.admin.OnlineUser
import io.wolftown.lauschig.absapi.admin.OnlineUsers
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.entities.LibraryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {
    private val viewModels = TestViewModels()
    private val sid = "0123456789abcdef"
    private val public = "https://abs.example.org/".toHttpUrl()
    private val account = MutableStateFlow<Account?>(Account(sid, "root", "root", true, ServerAddresses(null, public), emptySet()))
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Online(public, ConnectionState.Via.PUBLIC))
    private val calls = mutableListOf<String>()

    private val api = object : AdminApi {
        override suspend fun scanLibrary(libraryId: String) { calls += "scan:$libraryId" }
        override suspend fun scanItem(itemId: String) = ItemScanResult("UPTODATE").also { calls += "scanItem:$itemId" }
        override suspend fun matchItem(itemId: String, body: MatchRequest) = MatchResult(updated = true)
        override suspend fun onlineUsers() = OnlineUsers(listOf(OnlineUser("u1", "matthias"))).also { calls += "online" }
    }
    private val repo = AdminRepository({ flowOf(api) }, { flowOf(null) }, account, { _, _ -> null }, { _, _ -> }, {})
    private val libs = listOf(LibraryEntity(sid, "lib2", "Hörspiele", "book", 2), LibraryEntity(sid, "lib1", "Hörbücher", "book", 1))

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    @Test fun serverAdmin_listsLibraries_loadsWhoIsOnline_andScans() = runTest {
        val vm = viewModels.own(ServerAdminViewModel(account, connection, { flowOf(libs) }, repo))
        val events = mutableListOf<AdminEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { events += it } }
        val s = vm.state.value
        assertEquals(listOf("Hörbücher", "Hörspiele"), s.libraries.map { it.name })
        assertEquals(AdminAccess(admin = true, online = true), s.access)
        assertEquals("root", s.selfName)
        assertEquals("matthias", (s.online as OnlineState.Loaded).users.usersOnline.single().username)
        vm.scan(libs[1])
        assertEquals(listOf(AdminEvent.ScanStarted("Hörbücher")), events)
        assertTrue("scan:lib1" in calls)
    }

    @Test fun serverAdmin_offline_disablesAndDoesNotAsk() = runTest {
        connection.value = ConnectionState.Offline
        val vm = viewModels.own(ServerAdminViewModel(account, connection, { flowOf(libs) }, repo))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertFalse(vm.state.value.access.online)
        assertEquals(null, vm.state.value.online)
        assertTrue(calls.isEmpty())
        connection.value = ConnectionState.Online(public, ConnectionState.Via.PUBLIC)
        assertEquals(listOf("online"), calls)
    }

    @Test fun itemMenu_onlyForAdmins_andServerTitles() = runTest {
        val vm = viewModels.own(ItemAdminViewModel("li1", account, connection, repo))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertTrue(vm.state.value.visible)
        account.value = account.value!!.copy(isAdmin = false)
        assertFalse(vm.state.value.visible)

        account.value = account.value!!.copy(isAdmin = true)
        val local = viewModels.own(ItemAdminViewModel("local:0123456789abcdef01234567", account, connection, repo))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { local.state.collect {} }
        assertFalse(local.state.value.visible)
    }

    @Test fun itemMenu_rescanAndRematch_reportOnce() = runTest {
        val vm = viewModels.own(ItemAdminViewModel("li1", account, connection, repo))
        val events = mutableListOf<AdminEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { events += it } }
        vm.rescan()
        // The library has no provider in this fake (no library API): the match reports that instead of guessing one.
        vm.rematch()
        assertEquals(listOf(AdminEvent.ItemScanned("UPTODATE"), AdminEvent.Matched(MatchOutcome.NoProvider)), events)
        assertFalse(vm.state.value.busy)
    }
}
