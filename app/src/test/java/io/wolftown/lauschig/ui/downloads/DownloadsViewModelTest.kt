package io.wolftown.lauschig.ui.downloads

import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DownloadsViewModelTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private lateinit var db: LauschigDatabase
    private val viewModels = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private object NoOp : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: androidx.work.NetworkType?) {}
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = flowOf(null)
    }

    private suspend fun DownloadsViewModel.await(p: (DownloadsUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun freeSpace_isNotReadOnEveryProgressTick_onlyWhenTheDownloadsChange() = runTest {
        db.itemDao().upsertDetails(ItemEntity(s, "a", "l", "A"))
        db.audioFileDao().replaceFromServer(s, "a", listOf(AudioFileEntity(s, "a", 1, "i", 10.0, 1_000, "mp3")))
        db.downloadDao().upsert(DownloadEntity(s, "a", DownloadState.RUNNING, 1))
        val reads = AtomicInteger()
        val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val vm = viewModels.get(
            viewModelFactory { initializer { DownloadsViewModel(accounts, db.downloadDao(), NoOp, { reads.incrementAndGet(); 5_000L }, Dispatchers.Default) } },
            DownloadsViewModel::class,
        )
        // One subscriber for the whole test, as on screen (WhileSubscribed would restart the upstream between awaits).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        // A real dispatcher: the 30 s refresh must not be skipped through by the test's virtual time.
        assertEquals(5_000L, vm.await { it.loaded && it.total == 1 }.freeBytes)
        val before = reads.get()
        for (b in listOf(100L, 200L, 300L)) {
            db.audioFileDao().setProgress(s, "a", 1, b)
            vm.await { it.entries.singleOrNull()?.bytesOnDisk == b }
        }
        assertEquals("progress ticks must not query the file system", before, reads.get())
        db.downloadDao().setState(s, "a", DownloadState.COMPLETE, null)
        vm.await { it.entries.singleOrNull()?.status == EntryStatus.Complete }
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { while (reads.get() == before) kotlinx.coroutines.delay(10) } }
        assertEquals(before + 1, reads.get())
    }
}
