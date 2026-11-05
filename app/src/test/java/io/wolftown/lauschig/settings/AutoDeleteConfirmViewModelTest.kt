package io.wolftown.lauschig.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.cleanup.FinishedDownloadCleaner
import io.wolftown.lauschig.cleanup.FinishedDownloadCleanerTest
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Turning the automatic clean-up on asks when it would delete something; cancel leaves the setting as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoDeleteConfirmViewModelTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val viewModels = TestViewModels()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var download: DownloadSettings
    private lateinit var vm: SettingsViewModel

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        root = File(ctx.cacheDir, "adc-${System.nanoTime()}/downloads").apply { mkdirs() }
        val files = DownloadFiles(root, db.audioFileDao())
        download = DownloadSettings(ctx)
        val app = AppSettings(ctx)
        val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val seen = FinishedDownloadCleanerTest.InMemorySeen()
        val cleaner = FinishedDownloadCleaner(
            accounts, db.downloadDao(), db.progressDao()::observeFinishedKeys, download, app,
            DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, download), files::completedAt, seen,
            MutableStateFlow(null), { null }, { MutableStateFlow(emptyList()) }, db.audioFileDao()::observeLocalBytes,
        )
        // One heard title downloaded before it was finished: turning the clean-up on would delete it.
        val f = File(File(File(root, s), "b1"), "1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5)); setLastModified(System.currentTimeMillis() - 86_400_000) }
        db.audioFileDao().replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "i", 1.0, 5, "mp3")))
        db.audioFileDao().setLocal(s, "b1", 1, f.absolutePath, 5)
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.COMPLETE, 1))
        db.progressDao().replaceFromServer(ProgressEntity(s, "b1", 1.0, 1.0, true, 1))
        vm = viewModels.own(SettingsViewModel(app, download, PlaybackSettings(ctx), UiSettings(ctx), cleaner))
    }

    @After fun tearDown(): Unit = runBlocking {
        viewModels.clear()
        download.setAutoDeleteFinished(AutoDeleteMode.NEVER)
        db.close()
        root.parentFile!!.deleteRecursively()
        Dispatchers.resetMain()
    }

    private suspend fun await(p: (SettingsUiState) -> Boolean) = withTimeout(10_000) { vm.state.first(p) }

    @Test fun cancel_leavesTheSettingUnchanged(): Unit = runBlocking {
        vm.setAutoDelete(AutoDeleteMode.IMMEDIATELY)
        assertEquals(AutoDeleteConfirm(AutoDeleteMode.IMMEDIATELY, 1, 5), await { it.autoDeleteConfirm != null }.autoDeleteConfirm)
        vm.cancelAutoDelete()
        assertNull(await { it.autoDeleteConfirm == null }.autoDeleteConfirm)
        assertEquals(AutoDeleteMode.NEVER, download.autoDeleteFinished.first())
    }

    @Test fun confirm_switchesItOn_andTurningItOffNeverAsks(): Unit = runBlocking {
        vm.setAutoDelete(AutoDeleteMode.AFTER_3_DAYS)
        await { it.autoDeleteConfirm != null }
        vm.confirmAutoDelete()
        await { it.autoDeleteMode() == AutoDeleteMode.AFTER_3_DAYS && it.autoDeleteConfirm == null }
        // Already on: another delay is no new decision; off never deletes anything.
        vm.setAutoDelete(AutoDeleteMode.AFTER_7_DAYS)
        await { it.autoDeleteMode() == AutoDeleteMode.AFTER_7_DAYS }
        vm.setAutoDelete(AutoDeleteMode.NEVER)
        await { it.autoDeleteMode() == AutoDeleteMode.NEVER && it.autoDeleteConfirm == null }
    }

    private fun SettingsUiState.autoDeleteMode() = autoDelete
}
