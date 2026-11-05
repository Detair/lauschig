package io.wolftown.lauschig.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.cleanup.FinishedDownloadCleaner
import io.wolftown.lauschig.cleanup.FinishedDownloadCleanerTest
import io.wolftown.lauschig.cleanup.FinishedQuestion
import io.wolftown.lauschig.cleanup.FinishedQuestions
import io.wolftown.lauschig.cleanup.TitleHandover
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
 * "Als gehört markieren" on a downloaded title asks "Download löschen?" – also with the automatic clean-up on "Sofort".
 * Nothing is deleted before the answer; the question is noted on disk first, survives a rotation, and one a killed
 * process left open counts as "Behalten".
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
    private lateinit var manager: DefaultDownloadManager
    private lateinit var storeFile: File
    private lateinit var questions: FinishedQuestions
    private lateinit var vm: ItemActionsViewModel
    private lateinit var file: File
    private val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private val scopes = mutableListOf<CoroutineScope>()
    private var keepFails = false

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

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private var storeScope: CoroutineScope? = null

    /** A new instance on the same file: what a new process sees (the old one's store is closed first, as in a new process). */
    private fun questions() = FinishedQuestions(
        PreferenceDataStoreFactory.create(scope = scope().also { storeScope = it }, produceFile = { storeFile }),
        autoDeleteOn = { settings.autoDeleteFinished.first() != AutoDeleteMode.NEVER },
        keepEntry = { if (keepFails) throw java.io.IOException("disk full") else app.setCleanupIgnored(it, true) },
        deleteDownload = { sid, key -> manager.cancel(sid, key, deleteFiles = true) },
    )

    @Before fun setUp(): Unit = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TitleHandover.reset()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        root = File(ctx.cacheDir, "fq-${System.nanoTime()}/downloads").apply { mkdirs() }
        storeFile = File(root.parentFile, "questions.preferences_pb")
        val files = DownloadFiles(root, db.audioFileDao())
        settings = DownloadSettings(ctx)
        app = AppSettings(ctx)
        manager = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, settings)
        questions = questions()
        db.itemDao().upsertDetails(ItemEntity(s, "b1", "l", "Book", durationSec = 100.0))
        file = File(File(File(root, s), "b1"), "1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)); setLastModified(System.currentTimeMillis() - 86_400_000) }
        db.audioFileDao().replaceFromServer(s, "b1", listOf(AudioFileEntity(s, "b1", 1, "i", 100.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, "b1", 1, file.absolutePath, 3)
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.COMPLETE, 1))
        val actions = ProgressActions(db.progressDao(), db.itemDao(), db.episodeDao(), player, flowOf(null), object : SyncTrigger { override fun syncNow() {} }, settleMs = 0)
        vm = viewModels.own(ItemActionsViewModel(accounts, manager, player, actions, db.itemDao(), db.queueDao(), questions))
    }

    @After fun tearDown(): Unit = runBlocking {
        scopes.forEach { it.cancel() }
        viewModels.clear()
        settings.setAutoDeleteFinished(AutoDeleteMode.NEVER)
        app.cleanupIgnored.first().forEach { app.setCleanupIgnored(it, false) }
        db.close()
        root.parentFile!!.deleteRecursively()
        TitleHandover.reset()
        Dispatchers.resetMain()
    }

    private fun sofort() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        FinishedDownloadCleaner(
            accounts, db.downloadDao(), db.progressDao()::observeFinishedKeys, settings, app, manager, DownloadFiles(root, db.audioFileDao())::completedAt,
            FinishedDownloadCleanerTest.InMemorySeen(), MutableStateFlow(null), { null }, { MutableStateFlow(emptyList()) },
            db.audioFileDao()::observeLocalBytes, questions.holds, TitleHandover.state,
        ).start(scope())
    }

    private val q = FinishedQuestion(s, "b1")

    private fun finished() = runBlocking { db.progressDao().get(s, "b1")?.isFinished == true }

    @Test fun theQuestionIsDecidedFromAFreshRead_andNotedBeforeTheTitleIsFinished() = runBlocking {
        // Nothing collected the menu's download state: the question still comes.
        var heldWhenFinished: Boolean? = null
        val watch = scope().launch {
            db.progressDao().observeAll(s).filter { rows -> rows.any { it.itemId == "b1" && it.isFinished } }.first()
            heldWhenFinished = q in questions.holds.first()
        }
        vm.markFinished("b1")
        withTimeout(5_000) { questions.open.first { q in it } }
        withTimeout(5_000) { while (heldWhenFinished == null) delay(10) }
        watch.cancel()
        assertEquals(true, heldWhenFinished)
    }

    @Test fun noQuestion_forATitleWithoutACompleteDownload() = runBlocking {
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.PAUSED, 1))
        vm.markFinished("b1")
        withTimeout(5_000) { while (!finished()) delay(10) }
        delay(200)
        assertTrue(questions.open.value.isEmpty())
    }

    @Test fun withSofort_nothingGoesBeforeTheAnswer_keepProtectsIt() = runBlocking {
        sofort()
        vm.markFinished("b1")
        withTimeout(5_000) { while (!finished()) delay(10) }
        delay(700)
        assertTrue("held while the question is open", file.isFile)
        questions.answerKeep(q)
        assertTrue("$s:b1" in app.cleanupIgnored.first())
        assertTrue(questions.holds.first().isEmpty())
        delay(700)
        assertTrue("kept: the clean-up leaves it alone", file.isFile)
    }

    @Test fun withSofort_deleteDeletesNow() = runBlocking {
        sofort()
        vm.markFinished("b1")
        withTimeout(5_000) { questions.open.first { q in it } }
        questions.answerDelete(q)
        withTimeout(5_000) { while (file.exists()) delay(10) }
        assertTrue(questions.holds.first().isEmpty())
        assertFalse("$s:b1" in app.cleanupIgnored.first())
    }

    @Test fun aKeepThatCannotBeStored_failsClosed_theTitleStaysHeld() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        keepFails = true
        questions.ask(q)
        questions.answerKeep(q)
        assertTrue("the dialog closes", questions.open.value.isEmpty())
        assertTrue("but the title stays held", q in questions.holds.first())
    }

    @Test fun aQuestionAKilledProcessLeftOpen_isHeldAtTheStart_andCountsAsKeep() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        questions.ask(q) // then the process dies without an answer
        storeScope!!.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() }
        val next = questions() // the next process
        assertTrue(next.open.value.isEmpty())
        assertTrue("held from the first moment", q in next.holds.first())
        next.settleLeftovers()
        assertTrue("$s:b1" in app.cleanupIgnored.first())
        assertTrue(next.holds.first().isEmpty())
    }

    @Test fun withoutTheCleanUp_keepJustKeeps() = runBlocking {
        questions.ask(q)
        questions.answerKeep(q)
        assertFalse("no keep entry needed without the clean-up", "$s:b1" in app.cleanupIgnored.first())
        assertTrue(questions.holds.first().isEmpty())
    }

    @Test fun theDialog_survivesARecreation_andDismissingIsKeep() {
        runBlocking { settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY); questions.ask(q) }
        val restore = StateRestorationTester(rule)
        restore.setContent {
            LauschigTheme {
                val open by questions.open.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                open.firstOrNull()?.let { question ->
                    FinishedQuestionDialog(true, onDelete = { scope.launch { questions.answerDelete(question) } }, onKeep = { scope.launch { questions.answerKeep(question) } })
                }
            }
        }
        rule.onNodeWithText("Finished – delete the download now? “Keep” leaves it out of the automatic deletion. Your progress stays.").assertExists()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("finishedKeep").assertExists()
        // Dismissed with the back key (the dialog's own window).
        @Suppress("DEPRECATION") rule.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitUntil(5_000) { runBlocking { "$s:b1" in app.cleanupIgnored.first() } }
        rule.onNodeWithTag("finishedKeep").assertDoesNotExist()
        assertNotNull(runBlocking { db.downloadDao().get(s, "b1") })
    }
}
