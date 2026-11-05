package io.wolftown.lauschig.ui.common

import android.content.Context
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.playback.ProgressActions
import io.wolftown.lauschig.settings.AppSettings
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.LauschigTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Als gehört markieren" on a downloaded title asks "Download löschen?" – also with the automatic clean-up on "Sofort":
 * nothing is deleted before the answer, "Behalten" keeps the title from the clean-up, "Löschen" deletes now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FinishedQuestionTest {
    @get:Rule val rule = createComposeRule()
    private val s = "aaaaaaaaaaaaaaaa"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val viewModels = TestViewModels()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var settings: DownloadSettings
    private lateinit var app: AppSettings
    private lateinit var cleaner: FinishedDownloadCleaner
    private lateinit var vm: ItemActionsViewModel
    private lateinit var file: File
    private val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val player = object : PlayerController {
        override val state = MutableStateFlow(PlayerUiState())
        override fun play(serverId: String, itemId: String, startSec: Double?) {}
        override fun togglePlayPause() {}
        override fun pause() {}
        override fun seekBy(deltaSec: Int) {}
        override fun seekTo(globalSec: Double) {}
        override fun seekToChapter(index: Int) {}
        override fun setSpeed(speed: Float) {}
    }

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        root = File(ctx.cacheDir, "fq-${System.nanoTime()}/downloads").apply { mkdirs() }
        val files = DownloadFiles(root, db.audioFileDao())
        settings = DownloadSettings(ctx)
        app = AppSettings(ctx)
        val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val manager = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, settings)
        cleaner = FinishedDownloadCleaner(
            accounts, db.downloadDao(), db.progressDao()::observeFinishedKeys, settings, app, manager, files::completedAt,
            FinishedDownloadCleanerTest.InMemorySeen(), player.state.let { st -> kotlinx.coroutines.flow.flow { st.collect { emit(it.itemId) } } },
            { null }, { MutableStateFlow(emptyList()) }, db.audioFileDao()::observeLocalBytes,
        )
        db.itemDao().upsertDetails(ItemEntity(s, "b1", "l", "Book", durationSec = 100.0))
        file = File(File(File(root, s), "b1"), "1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)); setLastModified(System.currentTimeMillis() - 86_400_000) }
        db.audioFileDao().replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "i", 100.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, "b1", 1, file.absolutePath, 3)
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.COMPLETE, 1))
        val actions = ProgressActions(db.progressDao(), db.itemDao(), db.episodeDao(), player, flowOf(null), object : SyncTrigger { override fun syncNow() {} }, settleMs = 0)
        vm = viewModels.own(ItemActionsViewModel(accounts, manager, player, actions, db.itemDao(), db.queueDao(), settings, app, cleaner))
    }

    @After fun tearDown(): Unit = runBlocking {
        watcher.cancel()
        viewModels.clear()
        settings.setAutoDeleteFinished(AutoDeleteMode.NEVER)
        app.cleanupIgnored.first().forEach { app.setCleanupIgnored(it, false) }
        db.close()
        root.parentFile!!.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun markFinished() {
        rule.setContent {
            LauschigTheme {
                val host = rememberProgressActionHost("b1", vm, QuickDownload.Complete)
                host.Dialogs()
                LaunchedEffect(Unit) { host.run(ProgressEntry.MarkFinished) }
            }
        }
        rule.waitUntil(5_000) { runBlocking { db.progressDao().get(s, "b1")?.isFinished == true } }
    }

    private fun sofort() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        cleaner.start(watcher)
    }

    @Test fun withSofort_theQuestionComes_andNothingGoesBeforeTheAnswer_keepProtectsIt() {
        sofort()
        markFinished()
        rule.onNodeWithText("Finished – delete the download now? “Keep” leaves it out of the automatic deletion. Your progress stays.").assertExists()
        Thread.sleep(500)
        assertTrue("held while the question is open", file.isFile)
        rule.onNodeWithTag("finishedKeep").performClick()
        rule.waitUntil(5_000) { runBlocking { "$s:b1" in app.cleanupIgnored.first() } }
        Thread.sleep(500)
        assertTrue("kept: the clean-up leaves it alone", file.isFile)
        assertNotNull(runBlocking { db.downloadDao().get(s, "b1") })
    }

    @Test fun withSofort_deleteDeletesNow() {
        sofort()
        markFinished()
        rule.onNodeWithTag("finishedDelete").performClick()
        rule.waitUntil(5_000) { runBlocking { db.downloadDao().get(s, "b1") == null } && !file.exists() }
        assertFalse(runBlocking { "$s:b1" in app.cleanupIgnored.first() })
    }

    @Test fun withoutTheCleanUp_keepJustKeeps() {
        markFinished()
        rule.onNodeWithText("Finished – delete the download to free up space? Your progress stays.").assertExists()
        rule.onNodeWithTag("finishedKeep").performClick()
        Thread.sleep(300)
        assertTrue(file.isFile)
        assertFalse("no keep entry needed without the clean-up", runBlocking { "$s:b1" in app.cleanupIgnored.first() })
    }
}
