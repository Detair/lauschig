package io.wolftown.lauschig.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.download.DownloadFiles
import org.junit.After
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.AudioFileEntity
import io.wolftown.lauschig.db.entities.ChapterEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

internal class FakeFacade : PlayerFacade {
    var items: List<MediaItem> = emptyList()
    var snap = PlayerSnapshot(false, 0, 0, 1f)
    var started: Pair<Int, Long>? = null
    private var cb: () -> Unit = {}
    override suspend fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        setCalls++
        this.items = items; started = startIndex to startPositionMs; snap = snap.copy(mediaIndex = startIndex, positionMs = startPositionMs)
    }
    var setCalls = 0
    val replaced = mutableListOf<Int>()
    override fun replaceMediaItem(index: Int, item: MediaItem) { items = items.toMutableList().also { it[index] = item }; replaced += index }
    /** ExoPlayer's intent to play; kept across errors and queue swaps, so a rebuilt queue plays on only if it was true. */
    var playWhenReady = false
    var playCalls = 0
    /** What the service's tracker does on a pause: it saves the title's position, a moment later. */
    var onPause: (title: Pair<String, String>?) -> Unit = {}
    override fun play() { snap = snap.copy(isPlaying = true); playWhenReady = true; playCalls++ }
    override fun pause() { snap = snap.copy(isPlaying = false); playWhenReady = false; onPause(currentKey()) }
    override fun seekTo(mediaIndex: Int, positionMs: Long) { snap = snap.copy(mediaIndex = mediaIndex, positionMs = positionMs) }
    override fun setSpeed(speed: Float) { snap = snap.copy(speed = speed) }
    override fun snapshot() = snap
    /** The title of the playing queue entry, as the service's player reports it. */
    var key: Pair<String, String>? = null
    override fun currentKey() = key ?: items.getOrNull(snap.mediaIndex)?.mediaMetadata?.extras?.let { e ->
        e.getString(MediaItemFactory.EXTRA_SERVER_ID)!! to e.getString(MediaItemFactory.EXTRA_ITEM_ID)!!
    }
    override fun setOnChange(onChange: () -> Unit) { cb = onChange }
    var errorCb: (Int) -> Unit = {}
    var disconnectedCb: () -> Unit = {}
    override fun hasMedia() = items.isNotEmpty()
    var connects = 0
    override suspend fun connect() { connects++ }
    override fun setOnError(onError: (Int) -> Unit) { errorCb = onError }
    override fun streamBase() = items.firstNotNullOfOrNull { it.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_STREAM_BASE) }
    override fun setOnDisconnected(onDisconnected: () -> Unit) { disconnectedCb = onDisconnected }
    var stops = 0
    override suspend fun stop() { stops++; items = emptyList(); key = null; snap = PlayerSnapshot(false, 0, 0, snap.speed); playWhenReady = false; cb() }
    fun change() = cb()
    fun disconnect() { items = emptyList(); snap = PlayerSnapshot(false, 0, 0, 1f); disconnectedCb() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerControllerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private fun file(i: Int, dur: Double, path: String? = "/d/$i.mp3") = AudioFileEntity(s, "it", i, "ino$i", dur, 1, "mp3", path)
    private fun chapter(i: Int, st: Double, en: Double) = ChapterEntity(s, "it", i, st, en, "Chapter $i")
    private fun item(files: List<AudioFileEntity>, progress: ProgressEntity? = null) = ItemWithDetails(
        ItemEntity(s, "it", "lib", "Title", author = "Author", durationSec = 1800.0),
        listOf(chapter(0, 0.0, 500.0), chapter(1, 500.0, 1000.0), chapter(2, 1000.0, 1800.0)), files, progress, null,
    )

    private var dbBuilt = false
    private val db by lazy {
        dbBuilt = true
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build() // synchronous: the controller runs on the test scheduler
    }
    private val dlRoot = File(System.getProperty("java.io.tmpdir"), "pc-test-${System.nanoTime()}")

    @After fun cleanUp() {
        if (dbBuilt) db.close()
        dlRoot.deleteRecursively()
    }

    private val epKey = PlayableKey.episode("pod", "ep1")

    private suspend fun seedEpisode(withFile: Boolean, localPath: String? = null) {
        db.itemDao().upsertDetails(ItemEntity(s, "pod", "lib", "The Podcast"))
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "ep1", "Folge 1", durationSec = 900.0)))
        if (withFile) db.audioFileDao().replaceFromServer(s, epKey, listOf(AudioFileEntity(s, epKey, 1, "epino", 900.0, 1, "mp3")))
        if (localPath != null) db.audioFileDao().setLocal(s, epKey, 1, localPath, 1)
    }

    private fun TestScope.episodeController(facade: FakeFacade, base: String?, files: DownloadFiles, loadDetails: suspend (String, String) -> Unit = { _, _ -> }) =
        controller(
            facade, { null }, base,
            factory = MediaItemFactory({ true }) { sid, id -> files.playableCover(sid, id) },
            coverFile = { sid, id -> files.playableCover(sid, id) },
            loadDetails = loadDetails,
            loadById = { sid, id -> loadPlayable(db.itemDao(), db.episodeDao(), sid, id) },
        )

    @Test fun seekToAndPlay_seeksAndResumesAPausedTitle() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(listOf(file(0, 1800.0))))
        c.play(s, "it")
        c.pause()
        c.seekToAndPlay(1000.0)
        assertTrue(f.snap.isPlaying)
        assertEquals(1_000_000L, f.snap.positionMs)
        assertEquals(1000.0, c.state.value.positionSec, 0.0)
    }

    @Test fun titleStartedByTheService_isAdopted() = runTest {
        val f = FakeFacade()
        val second = ItemWithDetails(ItemEntity(s, "it2", "lib", "Second", durationSec = 600.0), emptyList(), listOf(AudioFileEntity(s, "it2", 0, "x", 600.0, 1, "mp3", "/d/x.mp3")), null, null)
        val first = item(listOf(file(0, 1800.0)))
        val c = controller(f, { first }, loadById = { _, id -> if (id == "it2") second else first })
        c.play(s, "it")
        // The service ended "it" and loaded the next queued title itself.
        f.key = s to "it2"
        f.snap = PlayerSnapshot(true, 0, 30_000, 1f)
        f.change()
        assertEquals("it2", c.state.value.itemId)
        assertEquals("Second", c.state.value.title)
        assertEquals(600.0, c.state.value.durationSec, 0.0)
        assertEquals(30.0, c.state.value.positionSec, 0.0)
    }

    @Test fun titleStartedByTheCar_withNothingLoadedInTheApp_isAdopted() = runTest {
        val f = FakeFacade()
        val carTitle = ItemWithDetails(ItemEntity(s, "car", "lib", "Vom Auto", durationSec = 600.0), emptyList(), listOf(AudioFileEntity(s, "car", 0, "x", 600.0, 1, "mp3", "/d/x.mp3")), null, null)
        val c = controller(f, { null }, loadById = { _, id -> if (id == "car") carTitle else null })
        // Android Auto started "car" inside the service; the app never played anything and was not connected.
        f.key = s to "car"
        f.snap = PlayerSnapshot(true, 0, 30_000, 1f)
        c.adoptFromService()
        assertEquals(1, f.connects)
        assertEquals("car", c.state.value.itemId)
        assertEquals("Vom Auto", c.state.value.title)
        assertEquals(30.0, c.state.value.positionSec, 0.0)
        assertTrue(c.state.value.isPlaying)
        // A later play/pause from the app acts on the adopted title.
        c.togglePlayPause()
        assertFalse(f.snap.isPlaying)
    }

    @Test fun playerEvent_withNothingLoaded_adoptsTheServiceTitle() = runTest {
        val f = FakeFacade()
        val carTitle = ItemWithDetails(ItemEntity(s, "car", "lib", "Vom Auto", durationSec = 600.0), emptyList(), listOf(AudioFileEntity(s, "car", 0, "x", 600.0, 1, "mp3", "/d/x.mp3")), null, null)
        val c = controller(f, { null }, loadById = { _, id -> if (id == "car") carTitle else null })
        f.key = s to "car"
        f.change()
        assertEquals("car", c.state.value.itemId)
    }

    // ---- the server address changes under a streamed title (home LAN → public address in the car) ----

    private val home = "http://192.168.1.10:13378/"
    private val public = "https://abs.example.org/"
    private fun streamedThree() = item(listOf(file(0, 600.0, null), file(1, 300.0, null), file(2, 900.0, null)))
    private val ioError = androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    private fun FakeFacade.uris() = items.map { it.localConfiguration!!.uri.toString() }

    @Test fun pausedStream_movesToTheNewAddress_rightAway_atItsPosition() = runTest {
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(home.toHttpUrl())
        val c = controller(f, { streamedThree() }, base = home, onlineBase = online)
        c.play(s, "it")
        c.seekTo(700.0) // second file, 100 s
        c.pause()
        online.value = public.toHttpUrl()
        assertTrue(f.uris().toString(), f.uris().all { it.startsWith(public) })
        assertEquals(1 to 100_000L, f.started)
        assertFalse(f.snap.isPlaying)
        assertEquals(2, f.setCalls)
    }

    @Test fun playingStream_keepsPlayingFromTheBuffer_andMovesOnTheNextNetworkError() = runTest {
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(home.toHttpUrl())
        val c = controller(f, { streamedThree() }, base = home, onlineBase = online)
        c.play(s, "it")
        c.seekTo(700.0)
        online.value = public.toHttpUrl()
        assertEquals(1, f.setCalls) // no hiccup while the buffer plays
        assertTrue(f.uris().all { it.startsWith(home) })
        // The buffer ran out at the old address: ExoPlayer stops with an error but keeps wanting to play.
        f.snap = f.snap.copy(isPlaying = false, positionMs = 130_000)
        f.errorCb(ioError)
        assertTrue(f.uris().all { it.startsWith(public) })
        assertEquals(1 to 130_000L, f.started)
        assertTrue("plays on by itself (playWhenReady kept)", f.playWhenReady)
        assertEquals("never forced to play", 1, f.playCalls)
    }

    @Test fun aNetworkErrorBeforeTheNewAddressIsKnown_movesOnceItIs_andPlaysOnOnlyIfStillWanted() = runTest {
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(home.toHttpUrl())
        val c = controller(f, { streamedThree() }, base = home, onlineBase = online)
        c.play(s, "it")
        f.snap = f.snap.copy(isPlaying = false)
        f.errorCb(ioError)
        assertEquals(1, f.setCalls)
        online.value = null // offline for a moment: nothing to do
        online.value = public.toHttpUrl()
        assertTrue(f.uris().all { it.startsWith(public) })
        assertTrue(f.playWhenReady)
        assertEquals(1, f.playCalls)
    }

    @Test fun anOldNetworkError_neverStartsPlaybackLater() = runTest {
        // A tunnel: network error. Playback recovers, the user pauses, and at home the Wi-Fi address appears.
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(public.toHttpUrl())
        val c = controller(f, { streamedThree() }, base = public, onlineBase = online)
        c.play(s, "it")
        f.snap = f.snap.copy(isPlaying = false)
        f.errorCb(ioError)
        f.snap = f.snap.copy(isPlaying = true) // recovered
        f.change()
        c.pause()
        online.value = home.toHttpUrl()
        assertTrue(f.uris().all { it.startsWith(home) }) // moved
        assertFalse(f.snap.isPlaying)
        assertFalse("stays paused", f.playWhenReady)
        assertEquals(1, f.playCalls)
    }

    @Test fun pausingWithAPendingAddress_movesTheQueueRightThen() = runTest {
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(home.toHttpUrl())
        val c = controller(f, { streamedThree() }, base = home, onlineBase = online)
        c.play(s, "it")
        c.seekTo(700.0)
        online.value = public.toHttpUrl() // while playing: waits
        assertEquals(1, f.setCalls)
        c.pause()
        assertTrue(f.uris().all { it.startsWith(public) })
        assertEquals(1 to 100_000L, f.started)
        assertFalse(f.playWhenReady)
        // A pause from elsewhere (notification, car) counts the same way.
        online.value = home.toHttpUrl()
        f.play(); f.change()
        online.value = public.toHttpUrl()
        f.pause(); f.change()
        assertTrue(f.uris().all { it.startsWith(public) })
    }

    @Test fun aTitleTheServiceStarted_movesToo() = runTest {
        // The car resumed the title at home; the app adopted it later, already knowing the new address.
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(public.toHttpUrl())
        f.items = MediaItemFactory({ true }) { _, _ -> null }.build(streamedThree(), home.toHttpUrl())
        f.key = s to "it"
        f.snap = PlayerSnapshot(false, 2, 60_000, 1f)
        val c = controller(f, { streamedThree() }, base = public, onlineBase = online)
        c.adoptFromService()
        assertTrue(f.uris().all { it.startsWith(public) })
        assertEquals(2 to 60_000L, f.started)
        assertFalse(f.snap.isPlaying)
    }

    @Test fun localFiles_neverMove() = runTest {
        val f = FakeFacade()
        val online = MutableStateFlow<okhttp3.HttpUrl?>(home.toHttpUrl())
        val c = controller(f, { item(threeFiles) }, base = home, onlineBase = online)
        c.play(s, "it")
        c.pause()
        online.value = public.toHttpUrl()
        f.errorCb(ioError)
        assertEquals(1, f.setCalls)
    }

    @Test fun episode_playsWithEpisodeTitleAndParentCover() = runTest {
        seedEpisode(withFile = true)
        val files = DownloadFiles(dlRoot, db.audioFileDao())
        files.coverFile(s, "pod").apply { parentFile!!.mkdirs(); writeText("jpg") }
        val f = FakeFacade()
        val c = episodeController(f, "https://abs.example/", files)
        c.play(s, epKey)
        assertEquals(epKey, c.state.value.itemId)
        assertEquals("Folge 1", c.state.value.title)
        assertEquals(900.0, c.state.value.durationSec, 0.0)
        assertEquals(files.coverFile(s, "pod").path, c.state.value.coverPath)
        val m = f.items.single()
        assertEquals("https://abs.example/api/items/pod/file/epino/download", m.localConfiguration!!.uri.toString())
        assertEquals("The Podcast", m.mediaMetadata.artist)
        assertEquals("Folge 1", m.mediaMetadata.title)
        assertEquals(files.coverFile(s, "pod").toURI().path, m.mediaMetadata.artworkUri!!.path)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun downloadedEpisode_playsOffline() = runTest {
        val audio = File(dlRoot, "ep.mp3").apply { parentFile!!.mkdirs(); writeText("x") }
        seedEpisode(withFile = true, localPath = audio.path)
        val f = FakeFacade()
        val c = episodeController(f, null, DownloadFiles(dlRoot, db.audioFileDao()))
        c.play(s, epKey)
        assertNull(c.state.value.error)
        assertEquals("file", f.items.single().localConfiguration!!.uri.scheme)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun episode_withoutFiles_online_loadsEpisodes() = runTest {
        seedEpisode(withFile = false)
        val loaded = mutableListOf<Pair<String, String>>()
        val f = FakeFacade()
        val c = episodeController(f, "https://abs.example/", DownloadFiles(dlRoot, db.audioFileDao())) { sid, id ->
            loaded += sid to id
            db.audioFileDao().replaceFromServer(sid, id, listOf(AudioFileEntity(sid, id, 1, "epino", 900.0, 1, "mp3")))
        }
        c.play(s, epKey)
        assertEquals(listOf(s to epKey), loaded)
        assertEquals(1, f.items.size)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun episode_withoutFiles_offline_failsNotDownloaded() = runTest {
        seedEpisode(withFile = false)
        val f = FakeFacade()
        val c = episodeController(f, null, DownloadFiles(dlRoot, db.audioFileDao()))
        c.play(s, epKey)
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
    }

    @Test fun episode_withoutPodcastRow_doesNotStart() = runTest {
        db.episodeDao().upsert(listOf(EpisodeEntity(s, "pod", "ep1", "Folge 1")))
        val f = FakeFacade()
        episodeController(f, "https://abs.example/", DownloadFiles(dlRoot, db.audioFileDao())).play(s, epKey)
        assertTrue(f.items.isEmpty())
    }

    private val threeFiles get() = listOf(file(0, 600.0), file(1, 300.0), file(2, 900.0))

    private fun TestScope.controller(facade: FakeFacade, item: ItemWithDetails?, base: String? = null) =
        controller(facade, { item }, base)

    private fun TestScope.controller(
        facade: FakeFacade,
        load: () -> ItemWithDetails?,
        base: String? = null,
        factory: MediaItemFactory = MediaItemFactory({ true }) { _, _ -> null },
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
        coverFile: (String, String) -> File? = { _, _ -> null },
        loadDetails: suspend (String, String) -> Unit = { _, _ -> },
        loadById: suspend (String, String) -> ItemWithDetails? = { _, _ -> load() },
        localCount: (String, String) -> Flow<Int> = { _, _ -> emptyFlow() },
        onStreamStarted: (String, String) -> Unit = { _, _ -> },
        streamStats: Flow<StreamStats> = emptyFlow(),
        downloadThrottled: Flow<Boolean> = emptyFlow(),
        rewindAfterPause: suspend (Long) -> Long = { 0 },
        clock: () -> Long = { 0 },
        onlineBase: Flow<okhttp3.HttpUrl?> = emptyFlow(),
        seekSteps: Flow<Pair<Int, Int>> = emptyFlow(),
        isSignedIn: suspend (String) -> Boolean = { true },
    ) = PlayerControllerImpl(
        scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        facade = facade,
        factory = factory,
        loadItem = loadById,
        onlineBaseUrl = { base?.toHttpUrl() },
        coverFile = coverFile,
        loadDetails = loadDetails,
        io = io,
        localCount = localCount,
        onStreamStarted = onStreamStarted,
        streamStats = streamStats,
        downloadThrottled = downloadThrottled,
        rewindAfterPause = rewindAfterPause,
        clock = clock,
        onlineBase = onlineBase,
        seekSteps = seekSteps,
        isSignedIn = isSignedIn,
    )

    /** A page that outlived a switch: A's title would stream with B's token and write under A. */
    @Test fun play_aTitleOfAnotherAccountThanTheSignedInOne_doesNotStart() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(listOf(file(0, 1800.0))) }, isSignedIn = { false })
        c.play(s, "it")
        assertEquals(0, f.setCalls)
        assertEquals(null, c.state.value.itemId)
    }

    /** Review I1: the service's player holds a title of the account that was left: the mini player does not take it over. */
    @Test fun aTitleOfAnotherAccountInTheService_isNotAdopted() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(listOf(file(0, 1800.0))) }, isSignedIn = { false })
        f.key = s to "it"
        c.adoptFromService()
        testScheduler.advanceUntilIdle()
        assertEquals(null, c.state.value.itemId)
    }

    @Test fun play_aDeviceTitle_startsWhoeverIsSignedIn() = runTest {
        val f = FakeFacade()
        val local = "${io.wolftown.lauschig.local.DeviceLibrary.ID_PREFIX}0123456789abcdef01234567"
        val row = ItemWithDetails(
            ItemEntity(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, local, "lib", "Vom Gerät", durationSec = 600.0),
            emptyList(), listOf(AudioFileEntity(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, local, 0, "x", 600.0, 1, "mp3", "/d/x.mp3")), null, null,
        )
        val c = controller(f, { row }, isSignedIn = { false })
        c.play(io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID, local)
        assertEquals(local, c.state.value.itemId)
    }

    @Test fun seekSteps_followTheSettings_andSurviveAStart() = runTest {
        val steps = MutableStateFlow(10 to 45)
        val f = FakeFacade()
        val c = controller(f, { item(listOf(file(0, 1800.0))) }, seekSteps = steps)
        assertEquals(10, c.state.value.seekBackSec)
        assertEquals(45, c.state.value.seekForwardSec)
        c.play(s, "it") // a start replaces the state
        assertEquals(10, c.state.value.seekBackSec)
        assertEquals(45, c.state.value.seekForwardSec)
        steps.value = 5 to 60
        assertEquals(5, c.state.value.seekBackSec)
        assertEquals(60, c.state.value.seekForwardSec)
        c.stop()
        assertEquals(5, c.state.value.seekBackSec)
    }

    @Test fun seekSteps_defaultFifteenBackThirtyForward() = runTest {
        val c = controller(FakeFacade(), null)
        assertEquals(15, c.state.value.seekBackSec)
        assertEquals(30, c.state.value.seekForwardSec)
    }

    @Test fun play_savedProgress_rewindsByThePauseSinceTheLastSave() = runTest {
        val pauses = mutableListOf<Long>()
        val f = FakeFacade()
        val c = controller(f, { item(threeFiles, ProgressEntity(s, "it", 950.0, 1800.0, false, 1_000)) }, rewindAfterPause = { pauses += it; 10_000 }, clock = { 61_000 })
        c.play(s, "it")
        assertEquals(listOf(60_000L), pauses)
        assertEquals(2 to 40_000L, f.started)
        // An explicit start (chapter, bookmark, "back to") is never moved.
        val f2 = FakeFacade()
        controller(f2, { item(threeFiles) }, rewindAfterPause = { 10_000 }, clock = { 61_000 }).play(s, "it", 950.0)
        assertEquals(2 to 50_000L, f2.started)
        // Never before the start of the title.
        val f3 = FakeFacade()
        controller(f3, { item(threeFiles, ProgressEntity(s, "it", 4.0, 1800.0, false, 1_000)) }, rewindAfterPause = { 10_000 }, clock = { 61_000 }).play(s, "it")
        assertEquals(0 to 0L, f3.started)
    }

    @Test fun play_withExplicitStart_seeksIntoRightFile() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 750.0)
        assertEquals(3, f.items.size)
        assertEquals(1 to 150_000L, f.started)
        assertTrue(c.state.value.isPlaying)
        assertEquals(750.0, c.state.value.positionSec, 0.001)
        assertEquals(1800.0, c.state.value.durationSec, 0.001)
        assertEquals(1, c.state.value.chapterIndex)
        assertEquals(500.0, c.state.value.chapterStartSec, 0.0)
        assertEquals(1000.0, c.state.value.chapterEndSec, 0.0)
    }

    @Test fun play_withoutStart_usesSavedProgress() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 950.0, 1800.0, false, 1)))
        c.play(s, "it")
        assertEquals(2 to 50_000L, f.started)
    }

    @Test fun play_finishedOrNoProgress_startsAtZero() = runTest {
        val f = FakeFacade()
        controller(f, item(threeFiles, ProgressEntity(s, "it", 1790.0, 1800.0, true, 1))).play(s, "it")
        assertEquals(0 to 0L, f.started)
        val f2 = FakeFacade()
        controller(f2, item(threeFiles)).play(s, "it")
        assertEquals(0 to 0L, f2.started)
    }

    @Test fun play_offlineNotDownloaded_setsError() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(listOf(file(0, 600.0), file(1, 300.0, path = null))), base = null)
        c.play(s, "it")
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_onlinePartiallyLocal_streamsMissing() = runTest {
        val f = FakeFacade()
        controller(f, item(listOf(file(0, 600.0), file(1, 300.0, path = null))), base = "https://abs.example/").play(s, "it")
        assertEquals("file", f.items[0].localConfiguration!!.uri.scheme)
        assertEquals("https", f.items[1].localConfiguration!!.uri.scheme)
    }

    @Test fun streaming_downloadOfPlayingFileFinishes_switchesToLocalAtSamePosition() = runTest {
        val f = FakeFacade()
        var current = item(listOf(file(0, 600.0, path = null)))
        val local = MutableStateFlow(0)
        val c = controller(f, { current }, base = "https://abs.example/", localCount = { _, _ -> local })
        c.play(s, "it", 120.0)
        assertTrue(c.state.value.isStreaming)
        f.snap = f.snap.copy(positionMs = 130_000L)

        current = item(listOf(file(0, 600.0)))
        local.value = 1

        assertEquals(2, f.setCalls)
        assertEquals("file", f.items[0].localConfiguration!!.uri.scheme)
        assertEquals(0 to 130_000L, f.started)
        assertTrue(f.snap.isPlaying)
        assertFalse(c.state.value.isStreaming)
    }

    @Test fun streaming_otherFileFinishes_replacedInPlaceWithoutInterruption() = runTest {
        val f = FakeFacade()
        var current = item(listOf(file(0, 600.0, path = null), file(1, 300.0, path = null)))
        val local = MutableStateFlow(0)
        val c = controller(f, { current }, base = "https://abs.example/", localCount = { _, _ -> local })
        c.play(s, "it", 10.0)

        current = item(listOf(file(0, 600.0, path = null), file(1, 300.0)))
        local.value = 1

        assertEquals(1, f.setCalls)
        assertEquals(listOf(1), f.replaced)
        assertEquals("https", f.items[0].localConfiguration!!.uri.scheme)
        assertEquals("file", f.items[1].localConfiguration!!.uri.scheme)
        assertTrue(c.state.value.isStreaming)
    }

    @Test fun local_downloadDeletedWhileLoaded_switchesToStreamAtSamePosition() = runTest {
        val f = FakeFacade()
        var current = item(listOf(file(0, 600.0)))
        val local = MutableStateFlow(1)
        val c = controller(f, { current }, base = "https://abs.example/", localCount = { _, _ -> local })
        c.play(s, "it", 60.0)
        assertFalse(c.state.value.isStreaming)
        f.snap = f.snap.copy(positionMs = 70_000L)

        current = item(listOf(file(0, 600.0, path = null)))
        local.value = 0

        assertEquals(2, f.setCalls)
        assertEquals("https", f.items[0].localConfiguration!!.uri.scheme)
        assertEquals(0 to 70_000L, f.started)
        assertTrue(c.state.value.isStreaming)
    }

    @Test fun local_downloadDeletedWhileOffline_pausesWithError() = runTest {
        val f = FakeFacade()
        var current = item(listOf(file(0, 600.0)))
        val local = MutableStateFlow(1)
        val c = controller(f, { current }, base = null, localCount = { _, _ -> local })
        c.play(s, "it")

        current = item(listOf(file(0, 600.0, path = null)))
        local.value = 0

        assertEquals(1, f.setCalls)
        assertFalse(f.snap.isPlaying)
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
    }

    @Test fun streaming_downloadFinishesAfterSwitchingTitle_leavesNewTitleAlone() = runTest {
        val f = FakeFacade()
        var current = item(listOf(file(0, 600.0, path = null)))
        val local = MutableStateFlow(0)
        val other = ItemWithDetails(ItemEntity(s, "other", "lib", "Other"), emptyList(), listOf(AudioFileEntity(s, "other", 0, "o", 100.0, 1, "mp3", "/d/o.mp3")), null, null)
        val c = controller(f, { current }, base = "https://abs.example/", localCount = { _, _ -> local },
            loadById = { _, id -> if (id == "other") other else current })
        c.play(s, "it")
        c.play(s, "other")
        val calls = f.setCalls

        current = item(listOf(file(0, 600.0)))
        local.value = 1

        assertEquals(calls, f.setCalls)
        assertEquals("other", c.state.value.itemId)
    }

    @Test fun seekBy_crossesFileBoundary_andClamps() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 590.0)
        c.seekBy(30)
        assertEquals(1, f.snap.mediaIndex); assertEquals(20_000L, f.snap.positionMs)
        c.seekBy(-30)
        assertEquals(0, f.snap.mediaIndex); assertEquals(590_000L, f.snap.positionMs)
        c.seekBy(-5000)
        assertEquals(0, f.snap.mediaIndex); assertEquals(0L, f.snap.positionMs)
    }

    @Test fun seekTo_mapsAbsoluteAcrossFileBoundary() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 10.0)
        c.seekTo(750.0)
        assertEquals(1 to 150_000L, f.snap.mediaIndex to f.snap.positionMs)
        c.seekTo(-5.0)
        assertEquals(0 to 0L, f.snap.mediaIndex to f.snap.positionMs)
    }

    @Test fun seekToChapter_andToggleAndSpeed() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        c.seekToChapter(2)
        assertEquals(2, f.snap.mediaIndex); assertEquals(100_000L, f.snap.positionMs)
        assertEquals(2, c.state.value.chapterIndex)
        c.togglePlayPause(); assertTrue(!c.state.value.isPlaying)
        c.togglePlayPause(); assertTrue(c.state.value.isPlaying)
        c.setSpeed(1.5f); assertEquals(1.5f, c.state.value.speed, 0f)
    }

    @Test fun playSameItemWithoutStart_justResumes() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it", 100.0)
        c.togglePlayPause()
        f.started = null
        c.play(s, "it")
        assertNull(f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun playAfterServiceDisconnect_rebuildsAndPlays() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 100.0, 1800.0, false, 1)))
        c.play(s, "it", 700.0)
        f.disconnect()
        assertTrue(!c.state.value.isPlaying)
        c.play(s, "it")
        assertEquals(3, f.items.size)
        assertEquals(0 to 100_000L, f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun play_onlineWithoutKnownFiles_loadsDetailsThenStreams() = runTest {
        val f = FakeFacade()
        var loaded: ItemWithDetails = item(emptyList())
        val calls = mutableListOf<String>()
        val c = controller(f, { loaded }, base = "https://abs.example/", loadDetails = { _, id ->
            calls += id; loaded = item(listOf(file(0, 600.0, path = null), file(1, 300.0, path = null)))
        })
        c.play(s, "it")
        assertEquals(listOf("it"), calls)
        assertEquals(2, f.items.size)
        assertEquals("https", f.items[0].localConfiguration!!.uri.scheme)
        assertTrue(c.state.value.isPlaying)
        assertTrue(c.state.value.isStreaming)
    }

    @Test fun play_showsLoadingRightAway_untilPlaybackStarts() = runTest {
        val f = FakeFacade()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var loaded: ItemWithDetails = item(emptyList())
        val c = controller(f, { loaded }, base = "https://abs.example/", loadDetails = { _, _ ->
            gate.await(); loaded = item(listOf(file(0, 600.0, path = null)))
        })
        c.play(s, "it")
        // Before the details arrive the mini player already shows the title with a loading state.
        assertTrue(c.state.value.isLoading)
        assertEquals("it", c.state.value.itemId)
        assertEquals("Title", c.state.value.title)
        gate.complete(Unit)
        assertTrue(c.state.value.isPlaying)
        assertTrue(!c.state.value.isLoading)
    }

    @Test fun play_error_endsLoading() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(emptyList()) }, base = "https://abs.example/", loadDetails = { _, _ -> })
        c.play(s, "it")
        assertTrue(!c.state.value.isLoading)
        assertEquals(R.string.error_item_not_playable, c.state.value.error)
    }

    @Test fun player_bufferingWhilePlaying_showsLoading() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        f.snap = f.snap.copy(isPlaying = false, isBuffering = true)
        c.seekBy(30)
        assertTrue(c.state.value.isLoading)
        f.snap = f.snap.copy(isPlaying = true, isBuffering = false)
        c.seekBy(0)
        assertTrue(!c.state.value.isLoading)
    }

    @Test fun play_onlineDetailsStillEmpty_setsErrorInsteadOfEmptyQueue() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(emptyList()) }, base = "https://abs.example/", loadDetails = { _, _ -> })
        c.play(s, "it")
        assertEquals(R.string.error_item_not_playable, c.state.value.error)
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_onlineDetailsLoadFails_setsError() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(emptyList()) }, base = "https://abs.example/", loadDetails = { _, _ -> throw java.io.IOException("boom") })
        c.play(s, "it")
        assertEquals(R.string.error_item_not_playable, c.state.value.error)
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_offlineWithoutKnownFiles_setsOfflineError() = runTest {
        val f = FakeFacade()
        val c = controller(f, { item(emptyList()) }, base = null, loadDetails = { _, _ -> error("must not load offline") })
        c.play(s, "it")
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_fullyLocal_isNotStreaming() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        assertTrue(!c.state.value.isStreaming)
    }

    @Test fun playerError_surfacesInUiState() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        f.errorCb(0)
        assertEquals(R.string.error_playback_failed, c.state.value.error)
        assertTrue(!c.state.value.isPlaying)
    }

    @Test fun resumeLoadedPausedItem_afterNewerSyncedProgress_seeksToSyncedPosition() = runTest {
        val f = FakeFacade()
        var saved = ProgressEntity(s, "it", 100.0, 1800.0, false, 1)
        val c = controller(f, { item(threeFiles, saved) })
        c.play(s, "it", 100.0)
        c.togglePlayPause() // paused at 100 s, the item stays loaded
        saved = ProgressEntity(s, "it", 950.0, 1800.0, false, 2) // sync merged a newer position from another device
        f.started = null
        c.play(s, "it")
        assertNull(f.started) // still the fast path, no rebuild
        assertEquals(2 to 50_000L, f.snap.mediaIndex to f.snap.positionMs)
        assertTrue(c.state.value.isPlaying)
        assertEquals(950.0, c.state.value.positionSec, 0.001)
    }

    @Test fun togglePlay_onLoadedPausedItem_afterNewerSyncedProgress_seeksToSyncedPosition() = runTest {
        val f = FakeFacade()
        var saved = ProgressEntity(s, "it", 100.0, 1800.0, false, 1)
        val c = controller(f, { item(threeFiles, saved) })
        c.play(s, "it", 100.0)
        c.togglePlayPause()
        saved = ProgressEntity(s, "it", 950.0, 1800.0, false, 2)
        c.togglePlayPause() // mini-player play button
        assertEquals(2 to 50_000L, f.snap.mediaIndex to f.snap.positionMs)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun resumeLoadedPausedItem_smallDifference_keepsPlayerPosition() = runTest {
        val f = FakeFacade()
        var saved = ProgressEntity(s, "it", 100.0, 1800.0, false, 1)
        val c = controller(f, { item(threeFiles, saved) })
        c.play(s, "it", 100.0)
        c.togglePlayPause()
        saved = ProgressEntity(s, "it", 130.0, 1800.0, false, 2)
        c.play(s, "it")
        assertEquals(0 to 100_000L, f.snap.mediaIndex to f.snap.positionMs)
    }

    @Test fun togglePlayPause_afterServiceStopped_restartsLastItem() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 700.0, 1800.0, false, 1)))
        c.play(s, "it")
        f.disconnect()
        assertEquals("it", c.state.value.itemId) // the mini-player still shows the title
        c.togglePlayPause()
        assertEquals(3, f.items.size)
        assertEquals(1 to 100_000L, f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun stop_pausesFirst_thenEmptiesTheQueue_andForgetsTheTitle() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 700.0, 1800.0, false, 1)))
        c.play(s, "it")
        assertTrue(f.snap.isPlaying)
        val stopJob = launch { c.stop() }
        testScheduler.runCurrent()
        // The pause comes first and is given a moment, so the service saves the position before the queue goes.
        assertFalse(f.snap.isPlaying)
        assertEquals(0, f.stops)
        testScheduler.advanceUntilIdle()
        stopJob.join()
        assertEquals(1, f.stops)
        assertTrue(f.items.isEmpty())
        assertEquals(PlayerUiState(), c.state.value)
        // Sign-out: play/pause can never bring the old account's title back.
        c.togglePlayPause()
        assertTrue(f.items.isEmpty())
        assertFalse(f.snap.isPlaying)
    }

    @Test fun stop_whilePaused_doesNotWait_andAfterADisconnectStillForgets() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        c.pause()
        f.disconnect()
        c.stop()
        assertEquals(1, f.stops)
        assertEquals(null, c.state.value.itemId)
        c.togglePlayPause()
        assertTrue(f.items.isEmpty())
    }

    @Test fun stop_cancelsAStartInProgress() = runTest {
        val f = FakeFacade()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val c = controller(f, { item(threeFiles) }, loadDetails = { _, _ -> gate.await() }, base = "http://h/", loadById = { _, _ -> item(emptyList()) })
        c.play(s, "it")
        assertTrue(c.state.value.isLoading)
        c.stop()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(f.items.isEmpty())
        assertEquals(PlayerUiState(), c.state.value)
    }

    // Task 1.2: back on a profile its last title waits, paused, in the mini player.

    @Test fun showPaused_showsTheTitleAtItsPosition_withoutTouchingThePlayer() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 700.0, 1800.0, false, 1)))
        c.showPaused(s, "it")
        assertEquals("it", c.state.value.itemId)
        assertEquals(700.0, c.state.value.positionSec, 0.0)
        assertEquals(1800.0, c.state.value.durationSec, 0.0)
        assertFalse(c.state.value.isPlaying)
        assertFalse(c.state.value.isLoading)
        assertEquals("nothing is loaded into the player", 0, f.setCalls)
        assertEquals(0, f.playCalls)
    }

    @Test fun showPaused_thenPlay_startsItAtItsPosition() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles, ProgressEntity(s, "it", 700.0, 1800.0, false, 1)))
        c.showPaused(s, "it")
        c.togglePlayPause()
        assertEquals(3, f.items.size)
        assertEquals(1 to 100_000L, f.started)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun showPaused_neverReplacesWhatIsLoaded() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.play(s, "it")
        c.showPaused(s, "other")
        assertEquals("it", c.state.value.itemId)
        assertTrue(c.state.value.isPlaying)
    }

    @Test fun showPaused_aTitleThatIsGone_showsNothing() = runTest {
        val f = FakeFacade()
        val c = controller(f, { null })
        c.showPaused(s, "gone")
        assertEquals(null, c.state.value.itemId)
    }

    @Test fun stop_endsAShownTitle() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.showPaused(s, "it")
        c.stop()
        assertEquals(null, c.state.value.itemId)
        c.togglePlayPause()
        assertTrue("nothing left to start", f.items.isEmpty())
    }

    @Test fun togglePlayPause_withNothingEverPlayed_doesNothing() = runTest {
        val f = FakeFacade()
        val c = controller(f, item(threeFiles))
        c.togglePlayPause()
        assertTrue(f.items.isEmpty())
    }

    @Test fun play_fileChecksRunOnIoDispatcher() = runTest {
        var onIo = false
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                onIo = true
                try { block.run() } finally { onIo = false }
            }
        }
        val checks = mutableListOf<Boolean>()
        val factory = MediaItemFactory({ checks += onIo; true }) { _, _ -> checks += onIo; null }
        val f = FakeFacade()
        val c = controller(f, { item(threeFiles) }, factory = factory, io = io, coverFile = { _, _ -> checks += onIo; null })
        c.play(s, "it")
        assertEquals(3, f.items.size)
        assertTrue(checks.isNotEmpty())
        assertTrue("file stats on main: $checks", checks.all { it })
    }

    @Test fun streamingStart_callsOnStreamStarted_once() = runTest {
        val f = FakeFacade()
        val started = mutableListOf<String>()
        val c = controller(f, { item(listOf(file(0, 600.0, path = null))) }, base = "https://abs.example/", onStreamStarted = { _, id -> started += id })
        c.play(s, "it")
        assertEquals(listOf("it"), started)
        // Resuming the loaded title is not a new start.
        c.togglePlayPause(); c.togglePlayPause()
        c.play(s, "it")
        assertEquals(listOf("it"), started)
    }

    @Test fun localStart_doesNotCallOnStreamStarted() = runTest {
        val f = FakeFacade()
        val started = mutableListOf<String>()
        val c = controller(f, { item(threeFiles) }, onStreamStarted = { _, id -> started += id })
        c.play(s, "it")
        assertTrue(f.snap.isPlaying)
        assertTrue(started.isEmpty())
    }

    @Test fun failedStreamingStart_doesNotCallOnStreamStarted() = runTest {
        val f = FakeFacade()
        val started = mutableListOf<String>()
        val c = controller(f, { item(listOf(file(0, 600.0, path = null))) }, base = null, onStreamStarted = { _, id -> started += id })
        c.play(s, "it")
        assertEquals(R.string.error_not_downloaded_offline, c.state.value.error)
        assertTrue(started.isEmpty())
    }

    @Test fun streamLoad_shownOnlyWhileAStreamLoads() = runTest {
        val f = FakeFacade()
        val stats = MutableStateFlow(StreamStats())
        val c = controller(f, { item(listOf(file(0, 600.0, path = null))) }, base = "https://abs.example/", streamStats = stats)
        c.play(s, "it")
        stats.value = StreamStats(active = true, phase = StreamPhase.PLAYING, bytes = 10, bytesPerSec = 100_000)
        assertNull(c.state.value.streamLoad) // playing

        f.snap = f.snap.copy(isPlaying = false, isBuffering = true)
        stats.value = StreamStats(active = true, phase = StreamPhase.STALLED, bytes = 250_000, expectedBytes = 1_250_000, bytesPerSec = 100_000)
        assertTrue(c.state.value.isLoading)
        assertEquals(StreamLoad(250_000, 1_250_000, 100_000, etaSec = 10), c.state.value.streamLoad)

        stats.value = StreamStats(active = true, phase = StreamPhase.STALLED, bytes = 300_000, bytesPerSec = 0)
        assertEquals(StreamLoad(300_000, null, 0, etaSec = null), c.state.value.streamLoad)

        f.snap = f.snap.copy(isPlaying = true, isBuffering = false)
        stats.value = StreamStats(active = true, phase = StreamPhase.PLAYING, bytes = 300_000, bytesPerSec = 0)
        assertNull(c.state.value.streamLoad)
    }

    @Test fun streamLoad_notForLocalPlayback() = runTest {
        val f = FakeFacade()
        val stats = MutableStateFlow(StreamStats())
        val c = controller(f, { item(threeFiles) }, streamStats = stats)
        c.play(s, "it")
        f.snap = f.snap.copy(isPlaying = false, isBuffering = true)
        stats.value = StreamStats(active = true, phase = StreamPhase.STALLED, bytes = 1)
        assertTrue(c.state.value.isLoading)
        assertNull(c.state.value.streamLoad)
    }

    @Test fun downloadThrottled_followsTheFlag_acrossStarts() = runTest {
        val f = FakeFacade()
        val throttled = MutableStateFlow(false)
        val c = controller(f, { item(listOf(file(0, 600.0, path = null))) }, base = "https://abs.example/", downloadThrottled = throttled)
        c.play(s, "it")
        assertFalse(c.state.value.downloadThrottled)
        throttled.value = true
        assertTrue(c.state.value.downloadThrottled)
        c.play(s, "it", 10.0)
        assertTrue(c.state.value.downloadThrottled)
        throttled.value = false
        assertFalse(c.state.value.downloadThrottled)
    }
}
