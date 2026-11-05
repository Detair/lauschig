package io.wolftown.lauschig.cleanup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
import java.util.concurrent.ConcurrentHashMap

/** Which finished downloads go by themselves, that only the files go, and what is never touched. */
@RunWith(RobolectricTestRunner::class)
class FinishedDownloadCleanerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val day = 24L * 60 * 60 * 1000
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var files: DownloadFiles
    private lateinit var settings: DownloadSettings
    private lateinit var app: AppSettings
    private lateinit var real: DefaultDownloadManager
    private val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private val seen = InMemorySeen()
    private val loaded = MutableStateFlow<String?>(null)
    private val queue = MutableStateFlow<List<String>>(emptyList())
    private val questions = MutableStateFlow<Set<FinishedQuestion>>(emptySet())
    @Volatile private var last: LastPlayed? = null
    private var scope: CoroutineScope? = null
    /** The device's clock for this test: real time, so file times and "seen" times compare like on a device. */
    private val now get() = System.currentTimeMillis()

    /** Like [DataStoreFinishedSeen], in memory; [at] presets when a key was first seen. */
    class InMemorySeen : FinishedSeenStore {
        val times = ConcurrentHashMap<String, Long>()
        fun at(key: String, time: Long) { times[key] = time }
        override suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long> {
            times.keys.retainAll(finished)
            finished.forEach { times.putIfAbsent(it, now) }
            return HashMap(times)
        }
        override suspend fun forget(serverId: String) = times.clear()
    }

    @Before fun setUp() {
        TitleHandover.reset()
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        root = File(ctx.cacheDir, "fdc-${System.nanoTime()}/downloads").apply { mkdirs() }
        files = DownloadFiles(root, db.audioFileDao())
        settings = DownloadSettings(ctx)
        app = AppSettings(ctx)
        real = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, settings)
    }

    @After fun tearDown(): Unit = runBlocking {
        scope?.cancel()
        TitleHandover.reset()
        settings.setAutoDeleteFinished(s, AutoDeleteMode.NEVER)
        app.cleanupIgnored.first().forEach { app.setCleanupIgnored(it, false) }
        db.close()
        root.parentFile!!.deleteRecursively()
    }

    private fun cleaner(
        manager: DownloadManager = real, recheckMs: Long = 60_000, graceMs: Long = TitleHandover.GRACE_MS, store: FinishedSeenStore = seen,
        queueOf: (String) -> kotlinx.coroutines.flow.Flow<List<String>> = { queue }, clock: () -> Long = System::currentTimeMillis,
    ) = FinishedDownloadCleaner(
        accounts, db.downloadDao(), db.progressDao()::observeFinishedKeys, settings, app, manager, files::completedAt, store,
        loaded, { last }, queueOf, db.audioFileDao()::observeLocalBytes, questions, TitleHandover.state,
        clock = clock, recheckMs = recheckMs, graceMs = graceMs,
    )

    /**
     * A download of [key] with [fileCount] files completed at [completedAt] (device clock, the files' times); its title
     * finished (position [position] of 100 s) and first seen finished at [seenAt]. [seenAt] null: not finished.
     */
    private suspend fun downloaded(
        key: String, completedAt: Long, seenAt: Long?, position: Double = 100.0, state: DownloadState = DownloadState.COMPLETE,
        lastUpdate: Long = completedAt, fileCount: Int = 1, duration: Double = 100.0, s: String = this.s,
    ): List<File> {
        val dir = File(File(root, s), key).apply { mkdirs() }
        val list = (1..fileCount).map { i -> File(dir, "$i.mp3").apply { writeBytes(ByteArray(3)); setLastModified(completedAt) } }
        db.audioFileDao().replaceFromServer(s, key, list.mapIndexed { i, _ -> AudioFileEntity(s, key, i + 1, "ino$i", 50.0, 3, "mp3") })
        list.forEachIndexed { i, f -> db.audioFileDao().setLocal(s, key, i + 1, f.absolutePath, 3) }
        db.downloadDao().upsert(DownloadEntity(s, key, state, completedAt))
        if (seenAt != null) {
            db.progressDao().replaceFromServer(ProgressEntity(s, key, position, duration, true, lastUpdate))
            seen.at(key, seenAt)
        }
        return list
    }

    private fun cand(key: String, completedAt: Long?, seenAt: Long?, position: Double = 100.0) = AutoDeleteCandidate(key, position, 100.0, completedAt, seenAt)

    // --- the rules

    @Test fun reallyFinished_isTheEndOrAnExplicitMark_notTheTrackersLastHalfPercent() {
        assertTrue("played to the end", AutoDeleteRules.reallyFinished(100.0, 100.0))
        assertTrue("saved a moment before the end", AutoDeleteRules.reallyFinished(98.5, 100.0))
        assertTrue("marked finished in the middle", AutoDeleteRules.reallyFinished(40.0, 100.0))
        assertTrue("marked finished without a position", AutoDeleteRules.reallyFinished(0.0, 0.0))
        // The tracker saves "finished" from 99.5 %: a pause there is no end.
        assertFalse(AutoDeleteRules.reallyFinished(36_000 * 0.996, 36_000.0))
    }

    @Test fun modes_countFromWhenTheDeviceSawTheTitleFinished() {
        val t = 1_000 * day
        val rows = listOf(cand("old", t - 30 * day, t - 8 * day), cand("week", t - 30 * day, t - 7 * day), cand("days", t - 30 * day, t - 3 * day), cand("fresh", t - 30 * day, t - 1_000))
        val keepNone: (String) -> Boolean = { false }
        assertEquals(emptyList<String>(), AutoDeleteRules.due(rows, AutoDeleteMode.NEVER, t, keepNone))
        assertEquals(listOf("old", "week", "days", "fresh"), AutoDeleteRules.due(rows, AutoDeleteMode.IMMEDIATELY, t, keepNone))
        assertEquals(listOf("old", "week", "days"), AutoDeleteRules.due(rows, AutoDeleteMode.AFTER_3_DAYS, t, keepNone))
        assertEquals(listOf("old", "week"), AutoDeleteRules.due(rows, AutoDeleteMode.AFTER_7_DAYS, t, keepNone))
        assertEquals(t + 3 * day - 1_000, AutoDeleteRules.nextDue(rows, AutoDeleteMode.AFTER_3_DAYS, t, keepNone))
        assertNull(AutoDeleteRules.nextDue(rows, AutoDeleteMode.IMMEDIATELY, t, keepNone))
    }

    @Test fun aDownloadCompletedAfterTheTitleWasFinished_doesNotCount_unknownTimesNeither() {
        val t = 1_000 * day
        assertFalse(AutoDeleteRules.counts(cand("again", completedAt = t, seenAt = t - day), { false }))
        assertTrue(AutoDeleteRules.counts(cand("normal", completedAt = t - day, seenAt = t), { false }))
        assertFalse("file time unknown (card out)", AutoDeleteRules.counts(cand("x", null, t), { false }))
        assertFalse("not seen finished yet", AutoDeleteRules.counts(cand("x", t - day, null), { false }))
    }

    @Test fun kept_protected_andDeviceTitles_neverCount_alsoUnderTheAccount() {
        val keep = AutoDeleteRules.keeper(s, setOf("$s:kept", "bbbbbbbbbbbbbbbb:other"), protected = setOf("loaded"))
        assertTrue(keep("kept"))
        assertTrue(keep("loaded"))
        assertFalse("kept on another server only", keep("other"))
        assertTrue("a device title's key under the account", keep("${DeviceLibrary.ID_PREFIX}0123456789abcdef01234567"))
        assertTrue("anything of the device library", AutoDeleteRules.keeper(DeviceLibrary.SERVER_ID, emptySet(), emptySet())("x"))
    }

    // --- one pass (the daily worker uses it too)

    @Test fun runOnce_deletesOnlyTheFiles_progressSessionsAndHistoryStay() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.AFTER_3_DAYS)
        val due = downloaded("due", completedAt = now - 10 * day, seenAt = now - 4 * day)
        val notYet = downloaded("notYet", completedAt = now - 10 * day, seenAt = now - day)
        val unheard = downloaded("unheard", completedAt = now - 10 * day, seenAt = null)
        val running = downloaded("running", completedAt = now - 10 * day, seenAt = now - 9 * day, state = DownloadState.RUNNING)
        db.sessionDao().upsert(LocalSessionEntity("sess", s, "due", 0.0, 1.0, 1.0, now - 4 * day, now - 4 * day))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO history_sessions (serverId, id, itemKey, startedAt, updatedAt, startTimeSec, currentTimeSec, timeListeningSec) VALUES (?, 'h1', 'due', 1, 2, 0, 1, 1)",
            arrayOf<Any>(s),
        )
        val before = counts()

        assertEquals(listOf("due"), cleaner().runOnce())

        assertNull(db.downloadDao().get(s, "due"))
        assertFalse(due.single().exists())
        assertNull(db.audioFileDao().forItem(s, "due").single().localPath)
        assertTrue(notYet.single().isFile && unheard.single().isFile && running.single().isFile)
        assertTrue(db.progressDao().get(s, "due")!!.isFinished)
        // Only the download row went; progress, sessions, history and everything else stay.
        assertEquals(before - "downloads", counts() - "downloads")
        assertEquals(before.getValue("downloads") - 1, counts().getValue("downloads"))
    }

    @Test fun runOnce_never_signedOut_orKept_deletesNothing() = runBlocking {
        downloaded("b1", completedAt = now - 30 * day, seenAt = now - 20 * day)
        assertEquals(emptyList<String>(), cleaner().runOnce()) // NEVER is the default
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        app.setCleanupIgnored("$s:b1", true)
        assertEquals(emptyList<String>(), cleaner().runOnce())
        app.setCleanupIgnored("$s:b1", false)
        val signedIn = accounts.acc.value
        accounts.acc.value = null
        assertEquals(emptyList<String>(), cleaner().runOnce())
        accounts.acc.value = signedIn
        assertEquals(listOf("b1"), cleaner().runOnce())
    }

    @Test fun runOnce_neverTheLoadedTheQueuedOrAHeldTitle() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        for (k in listOf("loaded", "queued", "held", "free")) downloaded(k, completedAt = now - 2 * day, seenAt = now - day)
        loaded.value = "loaded" // paused, stopped by the sleep timer or playing: loaded is loaded
        queue.value = listOf("queued")
        questions.value = setOf(FinishedQuestion(s, "held"))
        val c = cleaner()
        assertEquals(listOf("free"), c.runOnce())
        // Once the player lets go and the question is answered, they follow.
        loaded.value = "free"
        queue.value = emptyList()
        questions.value = emptySet()
        assertEquals(setOf("loaded", "queued", "held"), c.runOnce().toSet())
    }

    @Test fun withoutATitleInTheController_theLastPlayedOneStandsIn() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        downloaded("lastPlayed", completedAt = now - 2 * day, seenAt = now - day)
        // The car started it with the app's controller not connected, or the daily worker started the process.
        loaded.value = null
        last = LastPlayed(s, "lastPlayed", now)
        assertEquals(emptyList<String>(), cleaner().runOnce())
        // Another account's last title protects nothing here.
        last = LastPlayed("bbbbbbbbbbbbbbbb", "lastPlayed", now)
        assertEquals(listOf("lastPlayed"), cleaner().runOnce())
    }

    @Test fun aLoadedTitle_replacesTheLastPlayedOne_theOldOneGoes() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        downloaded("ended", completedAt = now - 2 * day, seenAt = now - day)
        last = LastPlayed(s, "ended", now) // the service notes the next title only at its first pause
        loaded.value = "next"
        assertEquals(listOf("ended"), cleaner().runOnce())
    }

    @Test fun aPauseAt99point6Percent_isNoEnd_theFilesStay_untilItReallyEnds() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        // A one-hour title paused 14 s before its end: the tracker already saved it finished.
        val f = downloaded("b1", completedAt = now - day, seenAt = now - 1_000, position = 3_600 * 0.996, duration = 3_600.0)
        assertEquals(emptyList<String>(), cleaner().runOnce())
        assertTrue(f.single().isFile)
        db.progressDao().replaceFromServer(ProgressEntity(s, "b1", 3_600.0, 3_600.0, true, now))
        assertEquals(listOf("b1"), cleaner().runOnce())
    }

    @Test fun aPausedDownloadResumedAfterTheTitleWasFinished_staysDespiteItsOldRequestTime() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        // Queued at T0 (download on play), file 1 done then; the title was finished by streaming at T1; the user resumed the
        // download at T3 and file 2 completed then. The row keeps requestedAt T0.
        val t0 = now - 10 * day
        val files2 = downloaded("b1", completedAt = t0, seenAt = now - 5 * day, fileCount = 2)
        files2[1].setLastModified(now - 1_000)
        assertEquals(t0, db.downloadDao().get(s, "b1")!!.requestedAt)
        assertEquals(emptyList<String>(), cleaner().runOnce())
        assertTrue(files2.all { it.isFile })
    }

    @Test fun theServersClock_doesNotMatter_onlyTheDevicesTimesAreCompared() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        // Progress synced from a server whose clock runs a year ahead, and one a year behind.
        downloaded("ahead", completedAt = now - 2 * day, seenAt = now - day, lastUpdate = now + 365 * day)
        downloaded("behind", completedAt = now - 2 * day, seenAt = now - day, lastUpdate = now - 365 * day)
        // Re-downloaded after it was seen finished; its server time claims it was finished later.
        downloaded("redownloaded", completedAt = now - 1_000, seenAt = now - day, lastUpdate = now + day)
        assertEquals(setOf("ahead", "behind"), cleaner().runOnce().toSet())
    }

    @Test fun finishedAgain_isSeenAnew() = runBlocking {
        val store = InMemorySeen()
        assertEquals(mapOf("a" to 10L), store.reconcile(s, setOf("a"), 10))
        assertEquals(mapOf("a" to 10L), store.reconcile(s, setOf("a"), 20))
        assertEquals(emptyMap<String, Long>(), store.reconcile(s, emptySet(), 30)) // started over
        assertEquals(mapOf("a" to 40L), store.reconcile(s, setOf("a"), 40))
    }

    @Test fun runOnce_coversEpisodes_andAKeptEpisodeStays() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val ep = downloaded("pod~ep1", completedAt = now - day, seenAt = now - 1_000)
        val kept = downloaded("pod~ep2", completedAt = now - day, seenAt = now - 1_000)
        app.setCleanupIgnored("$s:pod~ep2", true)
        assertEquals(listOf("pod~ep1"), cleaner().runOnce())
        assertFalse(ep.single().exists())
        assertTrue(kept.single().isFile)
    }

    @Test fun oneFailingDeletion_doesNotStopTheOthers() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        downloaded("bad", completedAt = now - day, seenAt = now - 1_000)
        val good = downloaded("good", completedAt = now - day, seenAt = now - 1_000)
        val failing = object : DownloadManager by real {
            override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {
                if (itemId == "bad") throw IllegalStateException("disk") else real.cancel(serverId, itemId, deleteFiles)
            }
        }
        assertEquals(listOf("good"), cleaner(failing).runOnce())
        assertFalse(good.single().exists())
    }

    @Test fun preview_countsWhatTurningItOnWouldDelete_keptAndRedownloadsLeftOut() = runBlocking {
        downloaded("a", completedAt = now - 3 * day, seenAt = now - 2 * day)
        downloaded("b", completedAt = now - 3 * day, seenAt = now - 2 * day, fileCount = 2)
        downloaded("kept", completedAt = now - 3 * day, seenAt = now - 2 * day)
        downloaded("again", completedAt = now - 1_000, seenAt = now - 2 * day)
        app.setCleanupIgnored("$s:kept", true)
        loaded.value = "a" // deleted later all the same: counted
        assertEquals(AutoDeletePreview(2, 9), cleaner().preview(AutoDeleteMode.AFTER_7_DAYS))
        assertEquals(AutoDeletePreview(0, 0), cleaner().preview(AutoDeleteMode.NEVER))
    }

    @Test fun theDailyWorker_hasTheSameGuards() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        downloaded("loaded", completedAt = now - 2 * day, seenAt = now - day)
        downloaded("queued", completedAt = now - 2 * day, seenAt = now - day)
        val free = downloaded("free", completedAt = now - 2 * day, seenAt = now - day)
        loaded.value = "loaded"
        queue.value = listOf("queued")
        val c = cleaner()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                FinishedDownloadsWorker(appContext, workerParameters, c)
        }
        val result = TestListenableWorkerBuilder<FinishedDownloadsWorker>(ctx).setWorkerFactory(factory).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(free.single().exists())
        assertNotNull(db.downloadDao().get(s, "loaded"))
        assertNotNull(db.downloadDao().get(s, "queued"))
    }

    // --- the watcher in the app

    /**
     * The end of a title with another one queued: the service starts the next title and the app's controller adopts it a
     * little later. The finished title must not be deleted while the controller still holds it (its source re-pick would
     * replace the next title) – only once the controller moved on.
     */
    @Test fun queueAdvance_theFinishedTitleGoes_onlyAfterThePlayerMovedOn() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val heldWhenDeleted = mutableListOf<Pair<String, String?>>()
        val recording = object : DownloadManager by real {
            override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {
                heldWhenDeleted += itemId to loaded.value
                real.cancel(serverId, itemId, deleteFiles)
            }
        }
        // A plays, B is queued; both downloaded long ago.
        loaded.value = "A"
        last = LastPlayed(s, "A", now)
        queue.value = listOf("B")
        val a = downloaded("A", completedAt = now - day, seenAt = null)
        downloaded("B", completedAt = now - day, seenAt = null)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(recording, recheckMs = 50, graceMs = 300).start(it) }
        // A ends: the tracker saves it finished at its full length.
        db.progressDao().replaceFromServer(ProgressEntity(s, "A", 100.0, 100.0, true, now))
        delay(300)
        assertTrue("still loaded at its end", a.single().isFile)
        // The service took B from the queue and started it; the controller still shows A (adopt pending).
        queue.value = emptyList()
        delay(300)
        assertTrue("the controller still holds A", a.single().isFile)
        // The controller adopted B (the service still has A as its last played title until B pauses): A may go once its
        // grace after leaving the player is over.
        loaded.value = "B"
        delay(100)
        assertTrue("still within its grace", a.single().isFile)
        withTimeout(10_000) { while (db.downloadDao().get(s, "A") != null || a.single().exists()) delay(20) }
        assertEquals(listOf("A" to "B"), heldWhenDeleted)
        assertTrue("its progress stays finished", db.progressDao().get(s, "A")!!.isFinished)
        assertNotNull("the next title is untouched", db.downloadDao().get(s, "B"))
    }

    @Test fun watcher_keepsWorkingAfterAFailedDeletion() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        var fail = true
        val flaky = object : DownloadManager by real {
            override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {
                if (fail) { fail = false; throw IllegalStateException("disk") }
                real.cancel(serverId, itemId, deleteFiles)
            }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(flaky).start(it) }
        downloaded("first", completedAt = now - day, seenAt = null)
        db.progressDao().replaceFromServer(ProgressEntity(s, "first", 100.0, 100.0, true, now))
        delay(300)
        downloaded("second", completedAt = now - day, seenAt = null)
        db.progressDao().replaceFromServer(ProgressEntity(s, "second", 100.0, 100.0, true, now))
        withTimeout(10_000) { while (db.downloadDao().get(s, "second") != null || db.downloadDao().get(s, "first") != null) delay(20) }
    }

    @Test fun watcher_deletesWhenTheWaitIsOver_onVirtualTime() = runBlocking {
        // The watcher runs on a test dispatcher: its waits are virtual, the database answers on real threads.
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val virtual = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        fun pump(ms: Long, until: () -> Boolean = { false }) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end && !until()) { scheduler.runCurrent(); Thread.sleep(10) }
        }
        val base = now
        settings.setAutoDeleteFinished(s, AutoDeleteMode.AFTER_3_DAYS)
        downloaded("soon", completedAt = base - 10 * day, seenAt = base - 3 * day + 10 * 60_000) // due 10 minutes after base
        cleaner(clock = { base + scheduler.currentTime }).start(virtual)
        try {
            pump(1_500)
            scheduler.advanceTimeBy(9 * 60_000)
            pump(500)
            assertNotNull("one minute before it is due", db.downloadDao().get(s, "soon"))
            scheduler.advanceTimeBy(2 * 60_000)
            pump(10_000) { runBlocking { db.downloadDao().get(s, "soon") } == null }
            assertNull(db.downloadDao().get(s, "soon"))
        } finally {
            virtual.cancel()
        }
    }

    // --- round 2: hand-over, accounts, robustness

    /**
     * A finished, downloaded title queued next survives its own start: the real queue advance picks it before it leaves
     * the queue, and the player holds it once the (slow) start is done. It plays from its files.
     */
    @Test fun aFinishedTitleQueuedNext_survivesItsOwnStart_withTheRealQueueAdvance() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val c = downloaded("C", completedAt = now - 2 * day, seenAt = now - day)
        loaded.value = "A"
        db.queueDao().add(s, "C", now)
        val queueRows = { sid: String -> db.queueDao().observe(sid).map { rows -> rows.map { it.itemKey } } }
        // No grace after leaving the queue: the pick alone must close the gap.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(queueOf = queueRows, recheckMs = 50, graceMs = 0).start(it) }
        delay(300)
        assertTrue("queued: held", c.single().isFile)
        val advancer = io.wolftown.lauschig.queue.QueueAdvancer(
            entries = { sid -> db.queueDao().entries(sid).map { it.itemKey } },
            remove = db.queueDao()::remove,
            check = { _, _ -> io.wolftown.lauschig.queue.QueueEntryState.PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, key ->
                delay(1_500) // resolve, setMediaItems, the controller's adopt: slow on purpose
                assertTrue("never deleted on its way into the player", c.single().isFile)
                TitleHandover.serviceHolds(key)
                loaded.value = key
                true
            },
            pick = { _, key -> TitleHandover.pick(key) },
        )
        assertEquals("C", advancer.onEnded(s, "A"))
        delay(500)
        assertTrue("loaded now: it plays from its files", c.single().isFile)
        assertNotNull(db.downloadDao().get(s, "C"))
        assertTrue(db.queueDao().entries(s).isEmpty())
    }

    /** The same start without the hand-over (no pick, no grace) loses the title: the test above is not vacuous. */
    @Test fun withoutTheHandover_theQueuedTitleWouldBeLostDuringItsStart() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val c = downloaded("C", completedAt = now - 2 * day, seenAt = now - day)
        loaded.value = "A"
        db.queueDao().add(s, "C", now)
        val queueRows = { sid: String -> db.queueDao().observe(sid).map { rows -> rows.map { it.itemKey } } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(queueOf = queueRows, recheckMs = 50, graceMs = 0).start(it) }
        delay(300)
        val advancer = io.wolftown.lauschig.queue.QueueAdvancer(
            entries = { sid -> db.queueDao().entries(sid).map { it.itemKey } },
            remove = db.queueDao()::remove,
            check = { _, _ -> io.wolftown.lauschig.queue.QueueEntryState.PLAYABLE },
            sleepEndOfChapter = { false },
            start = { _, _ -> withTimeout(5_000) { while (c.single().exists()) delay(20) }; true },
        )
        advancer.onEnded(s, "A")
        assertFalse(c.single().exists())
    }

    /** Runs [block] with a watcher on virtual time: its waits are virtual, the database answers on real threads. */
    private fun onVirtualTime(block: (scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, virtual: CoroutineScope, pump: (Long, () -> Boolean) -> Unit) -> Unit) {
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val virtual = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val pump = { ms: Long, until: () -> Boolean ->
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end && !until()) { scheduler.runCurrent(); Thread.sleep(10) }
        }
        try { block(scheduler, virtual, pump) } finally { virtual.cancel(); TitleHandover.clock = System::currentTimeMillis }
    }

    @Test fun aPickedTitleThatNothingElseHolds_goesWhenItsPickEnds_inTheWatcher_withFewPasses() = onVirtualTime { scheduler, virtual, pump ->
        val base = now
        val clock = { base + scheduler.currentTime }
        TitleHandover.clock = clock
        runBlocking { settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY) }
        val f = runBlocking { downloaded("P", completedAt = base - 2 * day, seenAt = base - day) }
        TitleHandover.pick("P") // a start that then failed: nothing else holds it
        val c = cleaner(clock = clock)
        c.start(virtual)
        pump(1_500) { false }
        scheduler.advanceTimeBy(TitleHandover.GRACE_MS - 1_000)
        pump(500) { false }
        assertTrue("still within its pick", f.single().isFile)
        scheduler.advanceTimeBy(2_000)
        pump(10_000) { !f.single().exists() }
        assertFalse(f.single().exists())
        println("passes until the pick ended: ${c.passes}")
        assertTrue("passes: ${c.passes}", c.passes <= 6)
    }

    /**
     * A counting title in the queue while another one plays (picked, then loaded): nothing runs every second – over ten
     * virtual minutes the watcher passes only on the changes and once when the pick ends.
     */
    @Test fun aQueuedCountingTitle_whileAnotherPlays_costsAHandfulOfPasses_notOnePerSecond() = onVirtualTime { scheduler, virtual, pump ->
        val base = now
        val clock = { base + scheduler.currentTime }
        TitleHandover.clock = clock
        runBlocking { settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY) }
        val queued = runBlocking { downloaded("C", completedAt = base - 2 * day, seenAt = base - day) }
        queue.value = listOf("C")
        TitleHandover.pick("A") // started from the list ...
        loaded.value = "A" // ... and loaded
        val c = cleaner(clock = clock)
        c.start(virtual)
        pump(1_500) { false }
        repeat(10) { scheduler.advanceTimeBy(60_000); pump(200) { false } }
        assertTrue("queued: kept", queued.single().isFile)
        println("passes over 10 virtual minutes: ${c.passes}")
        assertTrue("passes over 10 minutes: ${c.passes}", c.passes <= 6)
    }

    /** Family device: the clean-up is each account's own setting – a parent's "Sofort" never touches the child's downloads. */
    @Test fun theModeIsPerAccount_aParentsSofort_neverDeletesTheChildsDownloads() = runBlocking {
        val b = "bbbbbbbbbbbbbbbb"
        val store = DataStoreFinishedSeen(ctx).apply { forget(s); forget(b) }
        try {
            // The child's account B: seen since long ago, a title finished since (it would count for B).
            store.reconcile(b, emptySet(), now - 10 * day)
            val kid = downloaded("kid", completedAt = now - 9 * day, seenAt = null, s = b)
            db.progressDao().replaceFromServer(ProgressEntity(b, "kid", 100.0, 100.0, true, now - 8 * day))
            store.reconcile(b, setOf("kid"), now - 8 * day)
            // The parent (A, signed in) turns on "Sofort" – for A only.
            settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
            assertEquals(AutoDeleteMode.NEVER, settings.autoDeleteFinished(b).first())
            // Switch to the child.
            accounts.acc.value = Account(b, "u2", "kid", false, ServerAddresses(null, null), emptySet())
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(store = store).start(it) }
            delay(800)
            assertTrue("nothing of the child goes", kid.single().isFile)
            assertEquals(emptyList<String>(), cleaner(store = store).runOnce())
            // The child turns it on (after its own question): now only the child's goes.
            settings.setAutoDeleteFinished(b, AutoDeleteMode.IMMEDIATELY)
            withTimeout(10_000) { while (kid.single().exists()) delay(20) }
        } finally {
            store.forget(s); store.forget(b)
            settings.setAutoDeleteFinished(b, AutoDeleteMode.NEVER)
        }
    }

    @Test fun theOldDeviceWideSetting_belongsToTheAccountSignedInAtTheUpdate() = runBlocking {
        val active = "c${System.nanoTime().toString(16).takeLast(15).padStart(15, '0')}" // never stored before
        val other = "d${System.nanoTime().toString(16).takeLast(15).padStart(15, '0')}"
        settings.putLegacyAutoDelete(AutoDeleteMode.AFTER_7_DAYS)
        settings.migrateAutoDelete(active)
        assertEquals(AutoDeleteMode.AFTER_7_DAYS, settings.autoDeleteFinished(active).first())
        assertEquals(AutoDeleteMode.NEVER, settings.autoDeleteFinished(other).first())
        assertEquals(AutoDeleteMode.NEVER, settings.autoDeleteFinished(null).first())
        settings.migrateAutoDelete(other) // idempotent: the old value is gone
        assertEquals(AutoDeleteMode.NEVER, settings.autoDeleteFinished(other).first())
        settings.setAutoDeleteFinished(active, AutoDeleteMode.NEVER)
    }

    @Test fun aCorruptSeenStore_startsEmpty_withANewBaseline() = runBlocking {
        val file = File(root.parentFile, "seen-corrupt.preferences_pb").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            corruptionHandler = FinishedSeenStore.corruptionHandler(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), produceFile = { file },
        )
        assertEquals(mapOf("x" to FinishedSeenStore.BEFORE_BASELINE), DataStoreFinishedSeen(store).reconcile(s, setOf("x"), 100))
    }

    @Test fun aCorruptQuestionStore_startsEmpty_andEveryAccountTakesANewBaseline() = runBlocking {
        val seenStore = DataStoreFinishedSeen(ctx).apply { forget(s) }
        try {
            seenStore.reconcile(s, emptySet(), 1) // baseline
            assertEquals(mapOf("q" to 10L), seenStore.reconcile(s, setOf("q"), 10)) // would count
            val file = File(root.parentFile, "questions-corrupt.preferences_pb").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9)) }
            val questionStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                corruptionHandler = FinishedQuestions.corruptionHandler(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), produceFile = { file },
            )
            val questions = FinishedQuestions(questionStore, { true }, {}, { _, _ -> })
            assertEquals("it starts empty instead of failing", emptySet<FinishedQuestion>(), questions.holds.first())
            // The lost questions' titles may be among the finished ones: nothing finished now counts any more.
            assertEquals(mapOf("q" to FinishedSeenStore.BEFORE_BASELINE), seenStore.reconcile(s, setOf("q"), 20))
        } finally {
            seenStore.forget(s)
        }
    }

    @Test fun theServicesTitle_isHeld_evenBeforeTheAppAdoptsIt() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        downloaded("car", completedAt = now - 2 * day, seenAt = now - day)
        loaded.value = "other" // the app's controller still shows another title
        TitleHandover.serviceHolds("car")
        assertEquals(emptyList<String>(), cleaner().runOnce())
    }

    /**
     * Family device: switching to another account with the clean-up on deletes nothing it already had finished (a silent
     * baseline is taken) – only what is finished afterwards. The first account's times are kept for its return.
     */
    /** Both accounts have the clean-up on: a baseline still protects what an account had finished before it was seen. */
    @Test fun anAccountSwitch_deletesNothingOfTheOtherAccount_onlyWhatIsFinishedAfterwards() = runBlocking {
        val b = "bbbbbbbbbbbbbbbb"
        val store = DataStoreFinishedSeen(ctx).apply { forget(s); forget(b) }
        try {
            settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
            settings.setAutoDeleteFinished(b, AutoDeleteMode.IMMEDIATELY)
            // A: a title re-downloaded after it was finished (kept by the re-download rule) – seen before the switch.
            store.reconcile(s, emptySet(), now - 3 * day) // A's baseline
            val again = downloaded("again", completedAt = now - 1_000, seenAt = null)
            db.progressDao().replaceFromServer(ProgressEntity(s, "again", 100.0, 100.0, true, now))
            store.reconcile(s, setOf("again"), now - 2 * day)
            // B (kept on the device): finished downloads from before.
            val bOld = downloaded("old", completedAt = now - 10 * day, seenAt = null, s = b)
            db.progressDao().replaceFromServer(ProgressEntity(b, "old", 100.0, 100.0, true, now - 9 * day))
            accounts.acc.value = Account(b, "u2", "kid", false, ServerAddresses(null, null), emptySet())
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(store = store).start(it) }
            delay(800)
            assertTrue("B's old finished download stays", bOld.single().isFile)
            // Finished after the switch: goes.
            val bNew = downloaded("new", completedAt = now - 10 * day, seenAt = null, s = b)
            db.progressDao().replaceFromServer(ProgressEntity(b, "new", 100.0, 100.0, true, now))
            withTimeout(10_000) { while (bNew.single().exists()) delay(20) }
            assertTrue(bOld.single().isFile)
            // Back to A: its re-download is still judged by its old time, not stamped "now".
            accounts.acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet())
            delay(800)
            assertTrue("A's re-download stays", again.single().isFile)
        } finally {
            store.forget(s); store.forget(b)
            settings.setAutoDeleteFinished(b, AutoDeleteMode.NEVER)
        }
    }

    @Test fun theRealSeenStore_baselinePerServerKeepsOthers_andForgets() = runBlocking {
        val b = "bbbbbbbbbbbbbbbb"
        val store = DataStoreFinishedSeen(ctx).apply { forget(s); forget(b) }
        try {
            // First look at a server: everything finished then is old.
            assertEquals(mapOf("x" to FinishedSeenStore.BEFORE_BASELINE), store.reconcile(s, setOf("x"), 100))
            assertEquals(mapOf("x" to FinishedSeenStore.BEFORE_BASELINE, "y" to 200L), store.reconcile(s, setOf("x", "y"), 200))
            // Another server does not touch these entries.
            assertEquals(mapOf("z" to FinishedSeenStore.BEFORE_BASELINE), store.reconcile(b, setOf("z"), 300))
            assertEquals(mapOf("x" to FinishedSeenStore.BEFORE_BASELINE, "y" to 200L), store.reconcile(s, setOf("x", "y"), 400))
            // Started over, finished again: seen anew.
            store.reconcile(s, setOf("y"), 500)
            assertEquals(mapOf("x" to 600L, "y" to 200L), store.reconcile(s, setOf("x", "y"), 600))
            // Purged: a new baseline next time.
            store.forget(s)
            assertEquals(mapOf("y" to FinishedSeenStore.BEFORE_BASELINE), store.reconcile(s, setOf("y"), 700))
            assertEquals(mapOf("z" to FinishedSeenStore.BEFORE_BASELINE), store.reconcile(b, setOf("z"), 800))
        } finally {
            store.forget(s); store.forget(b)
        }
    }

    @Test fun aFailingPass_doesNotStopTheWatcher() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val f = downloaded("b1", completedAt = now - 2 * day, seenAt = now - day)
        var calls = 0
        val flaky = object : FinishedSeenStore {
            override suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long> {
                if (calls++ == 0) throw java.io.IOException("disk full")
                return seen.reconcile(serverId, finished, now)
            }
            override suspend fun forget(serverId: String) {}
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { cleaner(store = flaky, recheckMs = 100).start(it) }
        withTimeout(10_000) { while (f.single().exists()) delay(20) }
        assertTrue(calls >= 2)
    }

    @Test fun theDailyWorker_retriesWhenItsStoresFail() = runBlocking {
        settings.setAutoDeleteFinished(s, AutoDeleteMode.IMMEDIATELY)
        val broken = object : FinishedSeenStore {
            override suspend fun reconcile(serverId: String, finished: Set<String>, now: Long): Map<String, Long> = throw java.io.IOException("disk full")
            override suspend fun forget(serverId: String) {}
        }
        val c = cleaner(store = broken)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                FinishedDownloadsWorker(appContext, workerParameters, c)
        }
        assertEquals(ListenableWorker.Result.retry(), TestListenableWorkerBuilder<FinishedDownloadsWorker>(ctx).setWorkerFactory(factory).build().doWork())
    }

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
