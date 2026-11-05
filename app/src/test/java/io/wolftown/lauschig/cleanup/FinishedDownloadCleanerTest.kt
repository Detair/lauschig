package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.dao.FinishedDownload
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Which finished downloads go by themselves, that only the files go, and what is never touched. */
@RunWith(RobolectricTestRunner::class)
class FinishedDownloadCleanerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var files: DownloadFiles
    private lateinit var settings: DownloadSettings
    private lateinit var app: AppSettings
    private val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private var scope: CoroutineScope? = null

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        root = File(ctx.cacheDir, "fdc-${System.nanoTime()}/downloads").apply { mkdirs() }
        files = DownloadFiles(root, db.audioFileDao())
        settings = DownloadSettings(ctx)
        app = AppSettings(ctx)
    }

    @After fun tearDown(): Unit = runBlocking {
        scope?.cancel()
        settings.setAutoDeleteFinished(AutoDeleteMode.NEVER)
        app.cleanupIgnored.first().forEach { app.setCleanupIgnored(it, false) }
        db.close()
        root.parentFile!!.deleteRecursively()
    }

    private fun cleaner(clock: () -> Long = { now }) =
        FinishedDownloadCleaner(accounts, db.downloadDao(), settings, app, DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, settings), clock)

    /** A complete download of [key] (one file on disk) whose title is finished at [finishedAt]. */
    private suspend fun downloaded(key: String, requestedAt: Long, finishedAt: Long?, state: DownloadState = DownloadState.COMPLETE): File {
        val f = File(File(File(root, s), key), "1.mp3").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(3)) }
        db.audioFileDao().replaceFromServer(s, key, listOf(AudioFileEntity(s, key, 1, "ino", 1.0, 3, "mp3")))
        db.audioFileDao().setLocal(s, key, 1, f.absolutePath, 3)
        db.downloadDao().upsert(DownloadEntity(s, key, state, requestedAt))
        if (finishedAt != null) db.progressDao().replaceFromServer(ProgressEntity(s, key, 1.0, 1.0, true, finishedAt))
        return f
    }

    private fun row(key: String, requestedAt: Long, finishedAt: Long) = FinishedDownload(key, requestedAt, finishedAt)

    // --- the rules

    @Test fun modes_decideWhenAFinishedDownloadIsDue() {
        val rows = listOf(row("old", 0, now - 8 * day), row("week", 0, now - 7 * day), row("days", 0, now - 3 * day), row("fresh", 0, now - 1_000))
        val keepNone: (String) -> Boolean = { false }
        assertEquals(emptyList<String>(), AutoDeleteRules.due(rows, AutoDeleteMode.NEVER, now, keepNone))
        assertEquals(listOf("old", "week", "days", "fresh"), AutoDeleteRules.due(rows, AutoDeleteMode.IMMEDIATELY, now, keepNone))
        assertEquals(listOf("old", "week", "days"), AutoDeleteRules.due(rows, AutoDeleteMode.AFTER_3_DAYS, now, keepNone))
        assertEquals(listOf("old", "week"), AutoDeleteRules.due(rows, AutoDeleteMode.AFTER_7_DAYS, now, keepNone))
        // "Sofort" does not wait for a server clock that runs ahead.
        assertEquals(listOf("ahead"), AutoDeleteRules.due(listOf(row("ahead", 0, now + 5_000)), AutoDeleteMode.IMMEDIATELY, now, keepNone))
    }

    @Test fun aTitleDownloadedAgainAfterItWasFinished_staysUntilItIsFinishedAgain() {
        val again = row("again", requestedAt = now - day, finishedAt = now - 30 * day)
        assertEquals(emptyList<String>(), AutoDeleteRules.due(listOf(again), AutoDeleteMode.IMMEDIATELY, now, { false }))
        val finishedAgain = again.copy(finishedAt = now - 10)
        assertEquals(listOf("again"), AutoDeleteRules.due(listOf(finishedAgain), AutoDeleteMode.IMMEDIATELY, now, { false }))
    }

    @Test fun keptPlayingAndDeviceTitles_areNeverDue() {
        val dev = DeviceLibrary.SERVER_ID
        val keep = AutoDeleteRules.keeper(s, setOf("$s:kept", "bbbbbbbbbbbbbbbb:other"), playing = "playing")
        val rows = listOf(row("kept", 0, 1), row("playing", 0, 1), row("other", 0, 1))
        assertEquals(listOf("other"), AutoDeleteRules.due(rows, AutoDeleteMode.IMMEDIATELY, now, keep))
        val deviceKeep = AutoDeleteRules.keeper(dev, emptySet(), null)
        assertTrue(deviceKeep("local:anything"))
    }

    @Test fun nextDue_isTheEarliestWaitingDownload() {
        val rows = listOf(row("a", 0, now - 2 * day), row("b", 0, now - day), row("due", 0, now - 4 * day), row("again", now, now - day))
        assertEquals(now + day, AutoDeleteRules.nextDue(rows, AutoDeleteMode.AFTER_3_DAYS, now, { false }))
        assertNull(AutoDeleteRules.nextDue(rows, AutoDeleteMode.IMMEDIATELY, now, { false }))
        assertNull(AutoDeleteRules.nextDue(rows, AutoDeleteMode.NEVER, now, { false }))
    }

    // --- the runner

    @Test fun runOnce_deletesOnlyTheFiles_progressSessionsAndHistoryStay() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.AFTER_3_DAYS)
        val due = downloaded("due", requestedAt = now - 10 * day, finishedAt = now - 4 * day)
        val notYet = downloaded("notYet", requestedAt = now - 10 * day, finishedAt = now - day)
        val unheard = downloaded("unheard", requestedAt = now - 10 * day, finishedAt = null)
        val running = downloaded("running", requestedAt = now - 10 * day, finishedAt = now - 9 * day, state = DownloadState.RUNNING)
        db.sessionDao().upsert(LocalSessionEntity("sess", s, "due", 0.0, 1.0, 1.0, now - 4 * day, now - 4 * day))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO history_sessions (serverId, id, itemKey, startedAt, updatedAt, startTimeSec, currentTimeSec, timeListeningSec) VALUES (?, 'h1', 'due', 1, 2, 0, 1, 1)",
            arrayOf<Any>(s),
        )
        val before = counts()

        assertEquals(listOf("due"), cleaner().runOnce())

        assertNull(db.downloadDao().get(s, "due"))
        assertFalse(due.exists())
        assertNull(db.audioFileDao().forItem(s, "due").single().localPath)
        assertTrue(notYet.isFile && unheard.isFile && running.isFile)
        assertNotNull(db.progressDao().get(s, "due"))
        assertTrue(db.progressDao().get(s, "due")!!.isFinished)
        // Only the download row went; progress, sessions, history and everything else stay.
        assertEquals(before - "downloads", counts() - "downloads")
        assertEquals(before.getValue("downloads") - 1, counts().getValue("downloads"))
    }

    @Test fun runOnce_never_signedOut_orKept_deletesNothing() = runBlocking {
        downloaded("b1", requestedAt = 0, finishedAt = now - 30 * day)
        assertEquals(emptyList<String>(), cleaner().runOnce()) // NEVER is the default
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        app.setCleanupIgnored("$s:b1", true)
        assertEquals(emptyList<String>(), cleaner().runOnce())
        app.setCleanupIgnored("$s:b1", false)
        val signedIn = accounts.acc.value
        accounts.acc.value = null
        assertEquals(emptyList<String>(), cleaner().runOnce())
        accounts.acc.value = signedIn
        assertEquals(listOf("b1"), cleaner().runOnce())
    }

    @Test fun runOnce_coversEpisodes() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        val ep = downloaded("pod~ep1", requestedAt = 0, finishedAt = now - 1)
        assertEquals(listOf("pod~ep1"), cleaner().runOnce())
        assertFalse(ep.exists())
    }

    @Test fun watcher_deletesRightAway_butNotWhilePlaying() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.IMMEDIATELY)
        val playing = MutableStateFlow<String?>("b1")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(System::currentTimeMillis).start(it, playing) }
        val t = System.currentTimeMillis()
        val b1 = downloaded("b1", requestedAt = t - day, finishedAt = t)
        val b2 = downloaded("b2", requestedAt = t - day, finishedAt = t)
        awaitGone("b2")
        delay(300)
        assertTrue("the playing title keeps its files", b1.isFile)
        playing.value = null
        awaitGone("b1")
        assertFalse(b2.exists())
    }

    @Test fun watcher_deletesWhenTheWaitIsOver() = runBlocking {
        settings.setAutoDeleteFinished(AutoDeleteMode.AFTER_3_DAYS)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(System::currentTimeMillis).start(it, MutableStateFlow(null)) }
        val t = System.currentTimeMillis()
        downloaded("soon", requestedAt = t - 10 * day, finishedAt = t - 3 * day + 1_500)
        delay(500)
        assertNotNull("not due yet", db.downloadDao().get(s, "soon"))
        awaitGone("soon")
    }

    private suspend fun awaitGone(key: String) = withTimeout(10_000) { while (db.downloadDao().get(s, key) != null) delay(20) }

    /** Rows per table (Room's own tables left out). */
    private fun counts(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        val sql = db.openHelper.writableDatabase
        val tables = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        for (t in tables.filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }) {
            sql.query("SELECT COUNT(*) FROM `$t`").use { c -> c.moveToFirst(); out[t] = c.getInt(0) }
        }
        return out
    }
}
