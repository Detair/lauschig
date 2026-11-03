package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
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
}
