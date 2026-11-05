package io.wolftown.lauschig.download

import io.wolftown.lauschig.StubLibraryApi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.absapi.library.AudioFileDto
import io.wolftown.lauschig.absapi.library.BookMediaDto
import io.wolftown.lauschig.absapi.library.BookMetadataDto
import io.wolftown.lauschig.absapi.library.FileMetadataDto
import io.wolftown.lauschig.absapi.library.LibrariesResponse
import io.wolftown.lauschig.absapi.library.LibraryApi
import io.wolftown.lauschig.absapi.library.LibraryItemDto
import io.wolftown.lauschig.absapi.library.LibraryItemsPage
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.StreamMonitor
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.OnlineGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val item = "item1"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var files: DownloadFiles
    private val evil = "../../evil"
    private val a0 = ByteArray(100_000) { (it % 251).toByte() }
    private val a1 = ByteArray(40_000) { (it % 13).toByte() }
    private val cover = ByteArray(500) { 7 }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val prompts = DownloadPrompts()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().also { it.start() }
        root = File(ctx.cacheDir, "dl-test-${System.nanoTime()}")
        files = DownloadFiles(root, db.audioFileDao())
        runBlocking {
            db.itemDao().upsertDetails(ItemEntity(s, item, "l1", "A Book"))
            db.audioFileDao().replaceFromServer(
                s, item,
                listOf(
                    AudioFileEntity(s, item, 0, "ino0", 10.0, a0.size.toLong(), "mp3"),
                    AudioFileEntity(s, item, 1, "ino1", 10.0, a1.size.toLong(), "m4b"),
                ),
            )
            db.downloadDao().upsert(DownloadEntity(s, item, DownloadState.QUEUED, 1))
        }
    }

    @After fun tearDown() {
        server.shutdown(); db.close(); root.deleteRecursively()
    }

    private fun dispatch(
        fileBody: (index: Int, req: RecordedRequest) -> MockResponse = { i, r -> ranged(if (i == 0) a0 else a1, r) },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/cover") -> MockResponse().setBody(Buffer().write(cover))
                    p.contains("/file/ino0/") -> fileBody(0, request)
                    p.contains("/file/ino1/") -> fileBody(1, request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun ranged(data: ByteArray, req: RecordedRequest): MockResponse {
        val range = req.getHeader("Range")
        if (range == null) return MockResponse().setBody(Buffer().write(data))
        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
        return MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            .setBody(Buffer().write(data, from, data.size - from))
    }

    private suspend fun run(
        attempt: Int = 0, itemId: String = item, gate: OnlineGate = OnlineGate { true }, api: Boolean = false,
        library: LibraryApi? = null, monitor: StreamMonitor = StreamMonitor { 0L },
        signedIn: String? = s, client: OkHttpClient = OkHttpClient(),
    ): ListenableWorker.Result {
        val base = server.url("/")
        val env = DownloadEnvironment(flowOf(ConnectionState.Online(base, ConnectionState.Via.PUBLIC)), { signedIn }) { sid, _ -> clients += sid; client }
        val sync = LibrarySync({ flowOf(library) }, db.libraryDao(), db.itemDao(), db.chapterDao(), db.audioFileDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), { it() })
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = DownloadWorker(
                c, p, env, gate, sync, db.itemDao(), db.episodeDao(), db.audioFileDao(), db.downloadDao(), files, DownloadNotifier(c),
                monitor, prompts, switched,
            )
        }
        return TestListenableWorkerBuilder<DownloadWorker>(ctx, workDataOf(DownloadWorker.KEY_SERVER to s, DownloadWorker.KEY_ITEM to itemId))
            .setWorkerFactory(factory).setRunAttemptCount(attempt).build().doWork()
    }

    private fun itemDir() = File(root, "$s/$item")

    /** The accounts [DownloadEnvironment.client] was asked for. */
    private val clients = mutableListOf<String>()
    private val switchedScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val switched by lazy {
        SwitchedDownloads(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = switchedScope) { File(root, "switched-${System.nanoTime()}.preferences_pb") },
            db.downloadDao(),
            object : DownloadManager {
                override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) {
                    resumed += itemId
                }
                override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
                override fun observe(serverId: String, itemId: String) = flowOf<DownloadProgress?>(null)
            },
        )
    }
    private val resumed = mutableListOf<String>()

    @Test fun aWorkerOfAnAccountThatIsNotSignedIn_sendsNothing_waitsPaused_andGoesOnWhenItIsBack() = runBlocking {
        val result = run(signedIn = "bbbbbbbbbbbbbbbb")
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals("nothing goes out for it", 0, server.requestCount)
        assertEquals(emptyList<String>(), clients)
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(s, item)!!.state)
        // Its way back: the next time the account is active.
        switched.resume(s)
        assertEquals(listOf(item), resumed)
    }

    @Test fun aSwitchInTheMiddleOfADownload_keepsItsPart_waitsPaused_andGoesOnWhenItIsBack() = runBlocking {
        val refused = OkHttpClient.Builder().addInterceptor { throw io.wolftown.lauschig.net.AccountChangedException("made for another account") }.build()
        assertEquals("no retry, no failure count: the download is fine", ListenableWorker.Result.success(), run(client = refused))
        assertEquals(listOf(s), clients.distinct())
        val row = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.PAUSED, row.state)
        assertEquals(null, row.error)
        switched.resume(s)
        assertEquals(listOf(item), resumed)
    }

    @Test fun episode_downloadUsesParentItemIdInUrl() = runBlocking {
        val key = "$item~ep1"
        val ep = ByteArray(5_000) { (it % 7).toByte() }
        db.episodeDao().upsert(listOf(io.wolftown.lauschig.db.entities.EpisodeEntity(s, item, "ep1", "Pilot")))
        db.audioFileDao().replaceFromServer(s, key, listOf(AudioFileEntity(s, key, 1, "epino", 10.0, ep.size.toLong(), "mp3")))
        db.downloadDao().upsert(DownloadEntity(s, key, DownloadState.QUEUED, 1))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/cover") -> MockResponse().setBody(Buffer().write(cover))
                    p.contains("/file/epino/") -> ranged(ep, request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        assertEquals(ListenableWorker.Result.success(), run(itemId = key))
        val paths = requests.map { it.path.orEmpty() }
        assertTrue(paths.toString(), paths.any { it.startsWith("/api/items/$item/file/epino/") })
        assertTrue(paths.toString(), paths.any { it.startsWith("/api/items/$item/cover") })
        assertTrue(paths.none { it.contains("~") })
        // Stored under the episode key; the row and the file belong to the episode, not to the podcast.
        val row = db.audioFileDao().forItem(s, key).single()
        assertEquals(ep.size.toLong(), row.completedBytes)
        assertTrue(File(row.localPath!!).path.contains("$s/$key/"))
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, key)!!.state)
        assertTrue(files.coverFile(s, key).isFile)
    }

    @Test fun download_writesAllFilesAndCover_marksComplete() = runBlocking {
        dispatch()
        assertEquals(ListenableWorker.Result.success(), run())
        val rows = db.audioFileDao().forItem(s, item)
        assertEquals(listOf(a0.size.toLong(), a1.size.toLong()), rows.map { it.completedBytes })
        assertTrue(rows.all { it.localPath != null })
        assertTrue(File(itemDir(), "0.mp3").readBytes().contentEquals(a0))
        assertTrue(File(itemDir(), "1.m4b").readBytes().contentEquals(a1))
        assertTrue(File(itemDir(), "cover.jpg").readBytes().contentEquals(cover))
        assertEquals(0, itemDir().listFiles()!!.count { it.name.endsWith(".part") })
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
    }

    @Test fun interruptedDownload_isNotMarkedComplete_andResumes() = runBlocking {
        var first = true
        dispatch { i, r ->
            if (i == 0 && first) {
                first = false
                MockResponse().setBody(Buffer().write(a0)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else ranged(if (i == 0) a0 else a1, r)
        }
        assertEquals(ListenableWorker.Result.retry(), run())
        val part = File(itemDir(), "0.mp3.part")
        assertTrue(part.exists())
        val n = part.length()
        assertTrue(n in 1 until a0.size)
        val row = db.audioFileDao().forItem(s, item)[0]
        assertNull(row.localPath)
        assertEquals(DownloadState.QUEUED, db.downloadDao().get(s, item)!!.state)
        assertNotNull(db.downloadDao().get(s, item)!!.error)
        assertFalse(File(itemDir(), "0.mp3").exists())

        assertEquals(ListenableWorker.Result.success(), run(attempt = 1))
        assertTrue(requests.any { it.getHeader("Range") == "bytes=$n-" })
        assertTrue(File(itemDir(), "0.mp3").readBytes().contentEquals(a0))
        assertFalse(part.exists())
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
    }

    @Test fun serverWithoutRangeSupport_restartsFile() = runBlocking {
        File(itemDir(), "0.mp3.part").also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(5_000) { 9 })
        dispatch { i, _ -> MockResponse().setBody(Buffer().write(if (i == 0) a0 else a1)) } // always 200
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(requests.any { it.getHeader("Range") == "bytes=5000-" })
        assertTrue(File(itemDir(), "0.mp3").readBytes().contentEquals(a0))
    }

    @Test fun cancelWithDelete_removesFilesAndRows() = runBlocking {
        dispatch()
        run()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        mgr.cancel(s, item, deleteFiles = true)
        assertNull(db.downloadDao().get(s, item))
        assertFalse(itemDir().exists())
        assertTrue(db.audioFileDao().forItem(s, item).all { it.localPath == null && it.completedBytes == 0L })
    }

    @Test fun cancelKeepingFiles_pausesAndKeepsPart() = runBlocking {
        File(itemDir(), "0.mp3.part").also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(10))
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        mgr.cancel(s, item, deleteFiles = false)
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(s, item)!!.state)
        assertTrue(File(itemDir(), "0.mp3.part").exists())
    }

    @Test fun cleanup_removesUnreferencedFiles() = runBlocking {
        dispatch()
        run()
        val stale = File(itemDir(), "7.mp3").also { it.writeBytes(ByteArray(3)) }
        val stalePart = File(itemDir(), "8.mp3.part").also { it.writeBytes(ByteArray(3)) }
        files.cleanupOrphans(s, item)
        assertFalse(stale.exists())
        assertFalse(stalePart.exists())
        assertTrue(File(itemDir(), "0.mp3").exists() && File(itemDir(), "1.m4b").exists() && File(itemDir(), "cover.jpg").exists())
    }

    @Test fun failsAfterFiveAttempts() = runBlocking {
        dispatch { _, _ -> MockResponse().setResponseCode(500) }
        assertEquals(ListenableWorker.Result.retry(), run(attempt = 3))
        assertEquals(ListenableWorker.Result.failure(), run(attempt = 4))
        val d = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.FAILED, d.state)
        assertTrue(d.error!!.contains("500"))
    }

    @Test fun offline_retriesAndStaysQueued() = runBlocking {
        dispatch()
        assertEquals(ListenableWorker.Result.retry(), run(gate = { false }))
        assertEquals(DownloadState.QUEUED, db.downloadDao().get(s, item)!!.state)
        assertTrue(requests.isEmpty())
    }

    @Test fun missingAudioFileRows_withoutDetails_retries() = runBlocking {
        db.audioFileDao().replaceFromServer(s, item, emptyList())
        dispatch()
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(DownloadState.QUEUED, db.downloadDao().get(s, item)!!.state)
    }

    @Test fun observe_combinesStateAndBytes() = runBlocking {
        dispatch()
        run()
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        val p = mgr.observe(s, item).first()
        assertEquals(DownloadProgress(DownloadState.COMPLETE, (a0.size + a1.size).toLong(), (a0.size + a1.size).toLong()), p)
    }

    @Test fun maliciousExt_isSanitized() = runBlocking {
        db.audioFileDao().replaceFromServer(
            s, item,
            listOf(AudioFileEntity(s, item, 0, "ino0", 10.0, a0.size.toLong(), "../../evil")),
        )
        dispatch()
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(File(itemDir(), "0").readBytes().contentEquals(a0))
        assertFalse(File(root.parentFile, "evil").exists())
        assertTrue(db.audioFileDao().forItem(s, item)[0].localPath!!.startsWith(root.canonicalPath))
    }

    @Test fun maliciousItemId_isRejected_andNothingWrittenOutsideRoot() = runBlocking {
        db.downloadDao().upsert(DownloadEntity(s, evil, DownloadState.QUEUED, 1))
        dispatch()
        assertEquals(ListenableWorker.Result.failure(), run(itemId = evil))
        val d = db.downloadDao().get(s, evil)!!
        assertEquals(DownloadState.FAILED, d.state)
        assertEquals("invalid_path", d.error)
        assertFalse(File(root.parentFile, "evil").exists())
        assertTrue(requests.isEmpty())
    }

    @Test fun deleteItem_neverDeletesOutsideRoot() {
        val victim = File(root.parentFile, "victim").also { it.mkdirs() }
        val keep = File(victim, "keep.txt").also { it.writeText("x") }
        files.deleteItem(s, "../../victim")
        files.deleteItem("../..", "x")
        assertTrue(keep.exists())
        victim.deleteRecursively()
    }

    @Test fun storageFull_failsFast() = runBlocking {
        files = DownloadFiles(root, db.audioFileDao(), usableSpace = { 0L })
        dispatch()
        assertEquals(ListenableWorker.Result.failure(), run())
        val d = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.FAILED, d.state)
        assertEquals("storage_full", d.error)
    }

    @Test fun http404_failsFast() = runBlocking {
        dispatch { _, _ -> MockResponse().setResponseCode(404) }
        assertEquals(ListenableWorker.Result.failure(), run())
        val d = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.FAILED, d.state)
        assertEquals("http_404", d.error)
    }

    /** Serves fresh item details whose files have the current inos (ino0/ino1). */
    private inner class DetailsApi : StubLibraryApi() {
        var calls = 0
        override suspend fun libraries() = LibrariesResponse(emptyList())
        override suspend fun authors(id: String): io.wolftown.lauschig.absapi.library.AuthorsResponse = throw UnsupportedOperationException()
        override suspend fun items(libraryId: String, limit: Int, page: Int, minified: Int) = throw UnsupportedOperationException()
        override suspend fun recentEpisodes(id: String, limit: Int, page: Int): io.wolftown.lauschig.absapi.library.RecentEpisodesResponse = error("unused")
        override suspend fun item(id: String, expanded: Int): LibraryItemDto {
            calls++
            return LibraryItemDto(
                id, "l1", "book", 2,
                BookMediaDto(
                    BookMetadataDto(title = "A Book"), duration = 20.0,
                    audioFiles = listOf(
                        AudioFileDto(0, "ino0", 10.0, FileMetadataDto("a.mp3", ".mp3", a0.size.toLong())),
                        AudioFileDto(1, "ino1", 10.0, FileMetadataDto("b.m4b", ".m4b", a1.size.toLong())),
                    ),
                ),
            )
        }
    }

    @Test fun http404_withStaleFileIds_reloadsDetailsOnce_andCompletes() = runBlocking {
        // The library was rescanned on the server: our stored ino for file 0 no longer exists (404).
        db.audioFileDao().replaceFromServer(
            s, item,
            listOf(
                AudioFileEntity(s, item, 0, "stale0", 10.0, a0.size.toLong(), "mp3"),
                AudioFileEntity(s, item, 1, "ino1", 10.0, a1.size.toLong(), "m4b"),
            ),
        )
        dispatch()
        val details = DetailsApi()
        assertEquals(ListenableWorker.Result.success(), run(library = details))
        assertEquals(1, details.calls)
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
        assertTrue(db.audioFileDao().forItem(s, item).all { it.localPath != null })
    }

    @Test fun http404_afterDetailsReload_failsWithoutLooping() = runBlocking {
        dispatch { _, _ -> MockResponse().setResponseCode(404) }
        val details = DetailsApi()
        assertEquals(ListenableWorker.Result.failure(), run(library = details))
        assertEquals(1, details.calls)
        assertEquals("http_404", db.downloadDao().get(s, item)!!.error)
    }

    @Test fun http503_retries() = runBlocking {
        dispatch { _, _ -> MockResponse().setResponseCode(503) }
        assertEquals(ListenableWorker.Result.retry(), run())
        val d = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.QUEUED, d.state)
        assertEquals("http_503", d.error)
    }

    @Test fun coverFailure_stillCompletes() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                return when {
                    p.endsWith("/cover") -> MockResponse().setResponseCode(500)
                    p.contains("/file/ino0/") -> ranged(a0, request)
                    p.contains("/file/ino1/") -> ranged(a1, request)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
        assertFalse(File(itemDir(), "cover.jpg").exists())
    }

    @Test fun progress_isPersistedWhileDownloading() = runBlocking {
        dispatch { i, r -> ranged(if (i == 0) a0 else a1, r).let { if (i == 0) it.throttleBody(20_000, 250, java.util.concurrent.TimeUnit.MILLISECONDS) else it } }
        val job = launch(Dispatchers.IO) { run() }
        val mid = withTimeout(10_000) {
            var v: Long
            do {
                delay(100)
                v = db.audioFileDao().forItem(s, item)[0].completedBytes
            } while (v == 0L)
            v
        }
        assertTrue(mid in 1 until a0.size)
        assertNull(db.audioFileDao().forItem(s, item)[0].localPath)
        job.join()
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
    }

    @Test fun observe_neverQueued_emitsNull() = runBlocking {
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        assertNull(mgr.observe(s, "other").first())
    }

    @Test fun observe_afterCancelDelete_emitsNull() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
        mgr.cancel(s, item, deleteFiles = true)
        assertNull(mgr.observe(s, item).first())
    }

    @Test fun streamWithLowBuffer_throttlesButCompletes() = runBlocking {
        dispatch()
        // Plays with only 10 s buffered: downloads must yield.
        val monitor = StreamMonitor { 0L }.apply { update(active = true, playing = true, buffering = false, bufferedAheadMs = 10_000) }
        val started = System.nanoTime()
        assertEquals(ListenableWorker.Result.success(), run(monitor = monitor))
        val ms = (System.nanoTime() - started) / 1_000_000
        // 140 KB at 250 ms per 64 KB: at least two pauses.
        assertTrue("took ${ms}ms", ms >= 500)
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(s, item)!!.state)
        assertFalse(monitor.downloadThrottled.value)
    }

    @Test fun streamStalledDespiteThrottling_pausesDownloadKeepsPart_andAsks() = runBlocking {
        dispatch()
        // Every look at the clock moves it on by a second: the stall outlasts the 5 s after a few chunks.
        var t = 0L
        val monitor = StreamMonitor { t += 1_000; t }.apply {
            update(active = true, playing = true, buffering = false, bufferedAheadMs = 10_000)
            update(active = true, playing = false, buffering = true, bufferedAheadMs = 0)
        }
        assertEquals(ListenableWorker.Result.success(), run(monitor = monitor))
        val row = db.downloadDao().get(s, item)!!
        assertEquals(DownloadState.PAUSED, row.state)
        assertEquals(DownloadWorker.STREAM_PRIORITY, row.error)
        assertTrue(File(itemDir(), "0.mp3.part").isFile)
        assertNull(db.audioFileDao().forItem(s, item)[0].localPath)
        val prompt = prompts.prompt.value as DownloadPrompt.Contention
        assertEquals(item, prompt.itemId)
        assertEquals("A Book", prompt.title)
        assertFalse(monitor.downloadThrottled.value)
    }
}
