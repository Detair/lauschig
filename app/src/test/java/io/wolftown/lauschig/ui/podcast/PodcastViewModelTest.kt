package io.wolftown.lauschig.ui.podcast

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class PlayRecorder : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val plays = mutableListOf<String>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { plays += itemId }
    override fun togglePlayPause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PodcastViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val player = PlayRecorder()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Online("https://abs.example.org".toHttpUrl(), ConnectionState.Via.PUBLIC))
    private val loads = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val viewModels = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm(): PodcastViewModel {
        val store = FakeAccountStore()
        store.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                PodcastViewModel("pod", store, db.itemDao(), db.episodeDao(), db.progressDao(), db.downloadDao(), player, connection, { s, i -> loads += "$s/$i" }, covers)
            }
        }
        return viewModels.get(factory, PodcastViewModel::class)
    }

    private suspend fun seed(loaded: Boolean = true, numEpisodes: Int = 3) {
        val item = ItemEntity(serverId = sid, id = "pod", libraryId = "lib", title = "The Podcast", author = "Host", numEpisodes = numEpisodes)
        if (loaded) db.itemDao().upsertDetails(item) else db.itemDao().upsertFromList(listOf(item))
        db.episodeDao().upsert(
            listOf(
                EpisodeEntity(sid, "pod", "e1", "Folge 1", publishedAt = 1_000, durationSec = 600.0),
                EpisodeEntity(sid, "pod", "e2", "Folge 2", publishedAt = 3_000, durationSec = 600.0),
                EpisodeEntity(sid, "pod", "e3", "Folge 3", publishedAt = 2_000, durationSec = 600.0),
            ),
        )
    }

    private fun prog(ep: String, t: Double, fin: Boolean = false) =
        ProgressEntity(sid, PlayableKey.episode("pod", ep), t, 600.0, fin, 1, false)

    private suspend fun PodcastViewModel.await(p: (PodcastUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun episodes_newestFirst_withProgress() = runTest {
        seed()
        db.progressDao().mergeFromServer(listOf(prog("e3", 150.0), prog("e1", 600.0, fin = true)))
        val s = vm().await { it.found && it.episodes.size == 3 }
        assertEquals(listOf("pod~e2", "pod~e3", "pod~e1"), s.episodes.map { it.key })
        val e3 = s.episodes[1]
        assertEquals(450.0, e3.remainingSec!!, 0.001)
        assertEquals(0.25f, e3.progress, 0.001f)
        assertFalse(e3.finished)
        assertTrue(s.episodes[2].finished)
        assertEquals(null, s.episodes[0].remainingSec)
        assertEquals("The Podcast", s.title)
        assertEquals(3, s.episodeCount)
    }

    @Test fun filter_unplayed() = runTest {
        seed()
        db.progressDao().mergeFromServer(listOf(prog("e1", 600.0, fin = true), prog("e3", 100.0)))
        db.downloadDao().upsert(DownloadEntity(sid, PlayableKey.episode("pod", "e2"), DownloadState.COMPLETE, 1))
        val v = vm()
        v.await { it.episodes.size == 3 }
        v.setFilter(EpisodeFilter.UNPLAYED)
        // Heard episodes drop out; a started one is still on the list.
        assertEquals(listOf("pod~e2", "pod~e3"), v.await { it.filter == EpisodeFilter.UNPLAYED }.episodes.map { it.key })
        v.setFilter(EpisodeFilter.DOWNLOADED)
        assertEquals(listOf("pod~e2"), v.await { it.filter == EpisodeFilter.DOWNLOADED }.episodes.map { it.key })
    }

    @Test fun offline_showsStoredOnlyWithHint() = runTest {
        seed(loaded = false, numEpisodes = 10)
        connection.value = ConnectionState.Offline
        val s = vm().await { it.found }
        assertEquals(3, s.episodes.size)
        assertTrue(s.incomplete)
        assertEquals(10, s.episodeCount)
        assertTrue("no fetch while offline", loads.isEmpty())
    }

    @Test fun online_missingEpisodes_areLoadedOnce() = runTest {
        seed(loaded = false)
        val v = vm()
        v.await { it.found }
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { while (loads.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(listOf("$sid/pod"), loads.toList())
    }

    @Test fun play_usesEpisodeKey() = runTest {
        seed()
        val v = vm()
        v.await { it.found }
        v.play("pod~e2")
        assertEquals(listOf("pod~e2"), player.plays)
    }
}
