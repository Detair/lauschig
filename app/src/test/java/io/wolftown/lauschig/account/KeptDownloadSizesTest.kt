package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log
@Config(sdk = [35], application = android.app.Application::class)
class KeptDownloadSizesTest {
    private val signedIn = Account("aaaaaaaaaaaaaaaa", "u", "matthias", false, ServerAddresses(null, null), emptySet())
    private val fabian = KeptAccount("bbbbbbbbbbbbbbbb", "fabian", "abs")
    private val lukas = KeptAccount("cccccccccccccccc", "lukas", "nas")
    private val accounts = FakeAccountStore().apply { acc.value = signedIn }

    @Test fun twoWatchersAtOnce_walkEachDirectoryOnce() = runTest {
        val walks = AtomicInteger()
        val sizes = KeptDownloadSizes(accounts, FakeKeptAccounts(listOf(fabian, lukas)), { walks.incrementAndGet(); 1_000L }, backgroundScope)
        // The page's two ViewModels: the storage header and the "Downloads früherer Konten" section.
        val header = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val section = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var seenByHeader = emptyList<KeptDownloads>()
        header.launch { sizes.entries.collect { seenByHeader = it } }
        val seenBySection = sizes.entries.first()
        section.launch { sizes.entries.collect {} }
        assertEquals(listOf(KeptDownloads(fabian, 1_000), KeptDownloads(lukas, 1_000)), seenByHeader)
        assertEquals(seenByHeader, seenBySection)
        assertEquals("one walk per kept account, not per watcher", 2, walks.get())
    }

    @Test fun anUpstreamFailure_neverReachesTheScope_andTheNextVisitWorksAgain() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e })
        var broken = true
        // A corrupt DataStore file: the kept store throws when it is read.
        val store = object : KeptAccountStore by FakeKeptAccounts(listOf(fabian)) {
            override val accounts = flow<List<KeptAccount>> { if (broken) throw IOException("unreadable") else emit(listOf(fabian)) }
        }
        val sizes = KeptDownloadSizes(accounts, store, { 1_000L }, scope)
        assertEquals("an empty list instead of a failure", emptyList<KeptDownloads>(), withTimeout(1_000) { sizes.entries.first() })
        assertEquals("nothing for the application's uncaught-exception handler", emptyList<Throwable>(), uncaught)
        broken = false
        advanceTimeBy(6_000) // the grace period after the last watcher ends: the next visit starts afresh
        assertEquals(listOf(KeptDownloads(fabian, 1_000)), withTimeout(1_000) { sizes.entries.first() })
    }

    @Test fun aFailingWalk_isLogged_withTheServerId_andCountsZero() = runTest {
        ShadowLog.clear()
        val sizes = KeptDownloadSizes(accounts, FakeKeptAccounts(listOf(fabian, lukas)), { sid -> if (sid == fabian.serverId) throw IOException("unreadable") else 7L }, backgroundScope)
        assertEquals(listOf(KeptDownloads(fabian, 0), KeptDownloads(lukas, 7)), sizes.entries.first())
        val warning = ShadowLog.getLogs().single { it.tag == "KeptDownloads" }
        assertTrue(warning.msg, fabian.serverId in warning.msg)
        assertNotNull("the exception is logged too", warning.throwable)
    }

    @Test fun refresh_walksAgainAtOnce_withoutAChangeOfTheStore() = runTest {
        var size = 1_000L
        val walks = AtomicInteger()
        val sizes = KeptDownloadSizes(accounts, FakeKeptAccounts(listOf(fabian)), { walks.incrementAndGet(); size }, backgroundScope)
        var seen = emptyList<KeptDownloads>()
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)).launch { sizes.entries.collect { seen = it } }
        testScheduler.runCurrent()
        assertEquals(1_000L, seen.single().bytes)
        size = 5_000L // e.g. a delete that failed half way: the entry is put back unchanged
        sizes.refresh()
        testScheduler.runCurrent()
        assertEquals(5_000L, seen.single().bytes)
        assertEquals(2, walks.get())
    }
}
