package io.wolftown.lauschig.ui.detail

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.*
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakePlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val plays = mutableListOf<Triple<String, String, Double?>>()
    val chapterSeeks = mutableListOf<Int>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { plays += Triple(serverId, itemId, startSec) }
    override fun togglePlayPause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) { chapterSeeks += index }
    override fun setSpeed(speed: Float) {}
}

private class FakeDownloads : DownloadManager {
    val calls = mutableListOf<String>()
    override suspend fun enqueue(serverId: String, itemId: String) { calls += "enqueue" }
    override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) { calls += "cancel:$deleteFiles" }
    override fun observe(serverId: String, itemId: String): Flow<DownloadProgress?> = MutableStateFlow(null)
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ItemDetailViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val player = FakePlayer()
    private val downloads = FakeDownloads()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Online("https://abs.example.org".toHttpUrl(), ConnectionState.Via.PUBLIC))
    private val loadCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var loadFails = false

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    /** Owns every VM of a test so their Room observers are cancelled before the database closes. */
    private val viewModels = TestViewModels()

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm(): ItemDetailViewModel {
        val store = FakeAccountStore()
        store.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                ItemDetailViewModel("b1", store, db.itemDao(), downloads, player, connection, { s, i ->
                    loadCalls += "$s/$i"
                    if (loadFails) throw java.io.IOException("down")
                }, covers)
            }
        }
        return viewModels.get(factory, ItemDetailViewModel::class)
    }

    private suspend fun seed(loaded: Boolean = true, progress: ProgressEntity? = null, download: DownloadEntity? = null) {
        val e = ItemEntity(serverId = sid, id = "b1", libraryId = "l", title = "Book", durationSec = 300.0)
        if (loaded) db.itemDao().upsertDetails(e) else db.itemDao().upsertFromList(listOf(e))
        if (loaded) {
            db.chapterDao().replaceFromServer(sid, "b1", listOf(
                ChapterEntity(sid, "b1", 0, 0.0, 100.0, "One"),
                ChapterEntity(sid, "b1", 1, 100.0, 200.0, "Two"),
                ChapterEntity(sid, "b1", 2, 200.0, 300.0, "Three"),
            ))
        }
        progress?.let { db.progressDao().mergeFromServer(listOf(it)) }
        download?.let { db.downloadDao().upsert(it) }
    }

    /** The details-load trigger runs independently of [ItemDetailViewModel.state]; wait for it instead of racing it. */
    private suspend fun awaitLoads(n: Int) = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(10_000) { while (loadCalls.size < n) kotlinx.coroutines.delay(10) }
    }

    private fun prog(t: Double, fin: Boolean = false) = ProgressEntity(sid, "b1", t, 300.0, fin, 1, false)

    private suspend fun ItemDetailViewModel.await(p: (ItemDetailUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun primaryAction_reflectsProgress() = runTest {
        seed()
        assertEquals(PrimaryAction.Play, vm().await { it.found }.primary)

        db.progressDao().mergeFromServer(listOf(prog(120.0)))
        val v = vm()
        assertEquals(PrimaryAction.Continue(2), v.await { it.primary is PrimaryAction.Continue }.primary)
        v.onPrimary()
        assertEquals(Triple(sid, "b1", null), player.plays.last())

        db.progressDao().mergeFromServer(listOf(prog(300.0, fin = true).copy(lastUpdate = 2)))
        val v2 = vm()
        assertEquals(PrimaryAction.ListenAgain, v2.await { it.primary == PrimaryAction.ListenAgain }.primary)
        v2.onPrimary()
        assertEquals(Triple(sid, "b1", 0.0), player.plays.last())
    }

    @Test fun detailsMissing_online_triggersLoad_once() = runTest {
        seed(loaded = false)
        val v = vm()
        v.await { it.found }
        awaitLoads(1)
        assertEquals(listOf("$sid/b1"), loadCalls)
        // Re-emissions (progress arrives, connection flaps) must not re-trigger after the load was started successfully.
        db.progressDao().mergeFromServer(listOf(prog(10.0)))
        v.await { it.primary is PrimaryAction.Continue }
        connection.value = ConnectionState.Offline
        connection.value = ConnectionState.Online("https://abs.example.org".toHttpUrl(), ConnectionState.Via.PUBLIC)
        v.await { it.online }
        assertEquals(1, loadCalls.size)
    }

    @Test fun detailsMissing_failedLoad_retriesOnlyOnReconnect() = runTest {
        seed(loaded = false)
        loadFails = true
        val v = vm()
        v.await { it.found }
        awaitLoads(1)
        assertEquals(1, loadCalls.size)
        connection.value = ConnectionState.Offline
        connection.value = ConnectionState.Online("https://abs.example.org".toHttpUrl(), ConnectionState.Via.PUBLIC)
        v.await { it.online }
        awaitLoads(2)
        assertEquals(2, loadCalls.size)
    }

    @Test fun detailsMissing_offline_doesNotLoad() = runTest {
        seed(loaded = false)
        connection.value = ConnectionState.Offline
        val s = vm().await { it.found }
        assertTrue(loadCalls.isEmpty())
        assertFalse(s.detailsLoaded)
    }

    @Test fun offline_notDownloaded_notPlayableOffline() = runTest {
        seed()
        connection.value = ConnectionState.Offline
        val s = vm().await { it.found }
        assertFalse(s.playableOffline)
        assertFalse(s.online)
    }

    @Test fun chapterTap_seeksToChapterStart() = runTest {
        seed()
        val v = vm()
        v.await { it.chapters.size == 3 }
        // Not loaded in the player: starts playback at the chapter start.
        v.onChapter(1)
        assertEquals(Triple(sid, "b1", 100.0), player.plays.last())
        // Loaded in the player: seeks within the player.
        player.state.value = PlayerUiState(itemId = "b1", chapterIndex = 0, positionSec = 5.0)
        v.onChapter(2)
        assertEquals(listOf(2), player.chapterSeeks)
        assertEquals(1, player.plays.size)
    }

    @Test fun currentChapter_fromPlayerOtherwiseFromProgress() = runTest {
        seed(progress = prog(130.0))
        val v = vm()
        val s = v.await { it.chapters.any { c -> c.isCurrent } }
        assertEquals(1, s.chapters.indexOfFirst { it.isCurrent })
        assertEquals(30.0, s.chapters[1].positionInChapterSec!!, 0.001)
        player.state.value = PlayerUiState(itemId = "b1", chapterIndex = 2, positionSec = 250.5)
        val s2 = v.await { it.chapters[2].isCurrent }
        assertEquals(50.0, s2.chapters[2].positionInChapterSec!!, 0.001)
        // Another item playing does not highlight anything from the player.
        player.state.value = PlayerUiState(itemId = "other", chapterIndex = 2, positionSec = 250.0)
        assertTrue(v.await { it.chapters[1].isCurrent }.chapters[1].isCurrent)
    }

    @Test fun downloadStateMapping_andErrors() = runTest {
        seed(download = DownloadEntity(sid, "b1", DownloadState.FAILED, 1, "storage_full"))
        val d = object : DownloadManager by downloads {
            override fun observe(serverId: String, itemId: String) = MutableStateFlow<DownloadProgress?>(DownloadProgress(DownloadState.FAILED, 0, 10))
        }
        val store = FakeAccountStore().also { it.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet()) }
        val v = viewModels.own(ItemDetailViewModel("b1", store, db.itemDao(), d, player, connection, { _, _ -> }, CoverPathCache(DownloadFiles(tmp.newFolder("x"), db.audioFileDao())) { false }))
        assertEquals(DownloadUi.Failed(R.string.download_error_storage_full), v.await { it.found }.download)
        assertNotNull(v)
    }
}
