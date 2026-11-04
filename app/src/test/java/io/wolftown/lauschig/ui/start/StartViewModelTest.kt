package io.wolftown.lauschig.ui.start

import io.wolftown.lauschig.TestViewModels
import android.content.Context
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.EpisodeEntity
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.stats.StatsSessions
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class NoopPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override fun play(serverId: String, itemId: String, startSec: Double?) {}
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
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

    private val berlin = java.time.ZoneId.of("Europe/Berlin")
    private val calendar = io.wolftown.lauschig.stats.FakeStatsCalendar(
        io.wolftown.lauschig.stats.CalendarNow(java.time.LocalDate.of(2026, 11, 4), berlin, java.time.DayOfWeek.MONDAY),
    )

    private fun at(day: String, time: String = "09:00") = java.time.LocalDateTime.parse("${day}T$time").atZone(berlin).toInstant().toEpochMilli()

    private fun vm(): StartViewModel {
        val accounts = FakeAccountStore()
        accounts.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }
        val factory = viewModelFactory {
            initializer {
                StartViewModel(
                    covers, accounts, db.catalogDao(), db.libraryDao(), db.itemDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), NoopPlayer(), Catalog(),
                    StatsSessions(db.listeningSessionDao(), db.sessionDao()), calendar, UnconfinedTestDispatcher(),
                )
            }
        }
        return viewModels.get(factory, StartViewModel::class)
    }

    private fun item(id: String, series: String? = null, key: Double? = null, added: Long = 0, lib: String = "lib1") =
        ItemEntity(serverId = sid, id = id, libraryId = lib, title = id, seriesName = series, seriesSortKey = key, addedAt = added, durationSec = 100.0)

    private suspend fun progress(id: String, t: Double = 10.0, fin: Boolean = false, last: Long = 1, hidden: Boolean = false) =
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, id, t, 100.0, fin, last, false, hidden)))

    private suspend fun StartViewModel.await(p: (StartUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    private fun StartUiState.next() = nextInSeries.map { it.id }

    @Test fun start_nextInSeries_skipsHeardAndStarted() = runTest {
        db.itemDao().upsertFromList(
            listOf(
                item("a1", "Reihe A", 1.0), item("a2", "Reihe A", 2.0), item("a3", "Reihe A", 3.0), item("a4", "Reihe A", 4.0),
                item("b1", "reihe b ", 1.0), item("b2", "Reihe B", 2.0),            // case/whitespace variants are one series
                item("c1", "Reihe C", 1.0),                                       // nothing after the last volume
                item("d1", "Reihe D", 1.0), item("d2", "Reihe D", 2.0),           // nothing heard
                item("e1", "Reihe E", 1.0), item("e2", "Reihe E", 2.0), item("e3", "Reihe E", 3.0), // e2 has a zero-position progress row: not started, so it is the next volume
                item("f1", "Reihe F", null), item("f2", "Reihe F", 2.0),          // unkeyed volumes are ignored
            ),
        )
        progress("a1", fin = true, last = 5)
        progress("a3", t = 20.0, last = 7)                                         // a2 is skipped (below a3), a4 is next
        progress("b1", fin = true, last = 9)
        progress("c1", fin = true, last = 3)
        progress("e1", fin = true, last = 4)
        progress("e2", t = 0.0, last = 6)                                          // zero position is not started
        progress("f1", fin = true, last = 2)
        val s = vm().await { it.nextInSeries.isNotEmpty() }
        assertEquals(listOf("b2", "a4", "e2"), s.next())
    }

    @Test fun start_continueRow_excludesHidden_newestFirst() = runTest {
        db.itemDao().upsertFromList((1..12).map { item("i$it") } + item("hid") + item("fin") + item("none"))
        (1..12).forEach { progress("i$it", last = it.toLong()) }
        progress("hid", last = 100, hidden = true)
        progress("fin", fin = true, last = 101)
        progress("none", t = 0.0, last = 102)
        val s = vm().await { it.continueItems.isNotEmpty() }
        assertEquals((12 downTo 3).map { "i$it" }, s.continueItems.map { it.id })
    }

    @Test fun start_recentlyAdded_fromAddedAt() = runTest {
        db.itemDao().upsertFromList((1..12).map { item("n$it", added = it * 10L) } + item("unknown", added = 0))
        val s = vm().await { it.recentlyAdded.isNotEmpty() }
        assertEquals((12 downTo 3).map { "n$it" }, s.recentlyAdded.map { it.id })
    }

    @Test fun start_podcastLibraryItems_neverAppear() = runTest {
        db.libraryDao().upsertFromServer(
            listOf(LibraryEntity(sid, "lib1", "Bücher", "book", 0), LibraryEntity(sid, "pod", "Podcasts", "podcast", 1)),
        )
        db.itemDao().upsertFromList(
            listOf(
                item("b1", "Reihe A", 1.0), item("b2", "Reihe A", 2.0, added = 5),
                item("p1", "Cast", 1.0, added = 50, lib = "pod"), item("p2", "Cast", 2.0, added = 60, lib = "pod"),
            ),
        )
        progress("b1", fin = true, last = 2)
        progress("p1", fin = true, last = 9)
        val s = vm().await { it.nextInSeries.isNotEmpty() }
        assertEquals(listOf("b2"), s.next())
        assertEquals(listOf("b2"), s.recentlyAdded.map { it.id })
    }

    @Test fun start_sameSeriesNameInTwoLibraries_isTwoSeries() = runTest {
        db.libraryDao().upsertFromServer(
            listOf(LibraryEntity(sid, "lib1", "Bücher", "book", 0), LibraryEntity(sid, "lib2", "Hörspiele", "book", 1)),
        )
        db.itemDao().upsertFromList(
            listOf(
                item("book50", "Die drei ???", 50.0),                                  // nothing after it in this library
                item("play50", "Die drei ???", 50.0, lib = "lib2"), item("play51", "Die drei ???", 51.0, lib = "lib2"),
                item("x1", "Reihe X", 1.0), item("x2", "Reihe X", 2.0),
            ),
        )
        progress("book50", fin = true, last = 5)
        progress("x1", fin = true, last = 1)
        val s = vm().await { it.nextInSeries.isNotEmpty() }
        assertEquals(listOf("x2"), s.next())
    }

    private suspend fun podcastSetup() {
        db.libraryDao().upsertFromServer(
            listOf(LibraryEntity(sid, "lib1", "Bücher", "book", 0), LibraryEntity(sid, "pod", "Podcasts", "podcast", 1)),
        )
        db.itemDao().upsertFromList(listOf(item("p1", lib = "pod").copy(title = "Cast One"), item("p2", lib = "pod").copy(title = "Cast Two")))
    }

    private fun ep(item: String, id: String, at: Long, recent: Boolean = true) =
        EpisodeEntity(sid, item, id, "Folge $id", publishedAt = at, durationSec = 600.0, recent = recent)

    @Test fun newEpisodes_row_inProgressFirst() = runTest {
        podcastSetup()
        db.episodeDao().upsert(
            listOf(
                ep("p1", "new1", 300), ep("p1", "new2", 200), ep("p2", "heard", 250), ep("p2", "done", 260),
                ep("p2", "old", 10, recent = false), ep("p2", "hidden", 270),
            ),
        )
        progress(PlayableKey.episode("p2", "old"), t = 120.0, last = 5)       // started, no longer recent: still first
        progress(PlayableKey.episode("p2", "heard"), t = 30.0, last = 9)      // started, newer: ahead of "old"
        progress(PlayableKey.episode("p2", "done"), fin = true, last = 8)     // finished: never
        progress(PlayableKey.episode("p2", "hidden"), t = 30.0, last = 7, hidden = true) // hidden from continue and started: not shown
        val s = vm().await { it.newEpisodes.isNotEmpty() }
        assertEquals(
            listOf("p2~heard", "p2~old", "p1~new1", "p1~new2").map { it },
            s.newEpisodes.map { it.id },
        )
        val first = s.newEpisodes.first()
        assertEquals("Folge heard", first.title)
        assertEquals("Cast Two", first.author)
        assertEquals(0.3f, first.progress, 0.001f)
    }

    @Test fun noPodcastLibrary_noRow() = runTest {
        db.itemDao().upsertFromList(listOf(item("b1", added = 5)))
        db.episodeDao().upsert(listOf(ep("b1", "e1", 100)))   // stray episode rows of a non-podcast library
        val s = vm().await { it.recentlyAdded.isNotEmpty() }
        assertEquals(emptyList<String>(), s.newEpisodes.map { it.id })
    }

    @Test fun continueRow_excludesPodcasts() = runTest {
        podcastSetup()
        db.itemDao().upsertFromList(listOf(item("b1")))
        progress("b1", last = 2)
        progress("p1", last = 9)   // progress under the podcast's own id must not make the podcast a "continue" tile
        val s = vm().await { it.continueItems.isNotEmpty() }
        assertEquals(listOf("b1"), s.continueItems.map { it.id })
    }
    @Test fun start_continueRow_includesDeviceTitles_byLastListened() = runTest {
        val dev = io.wolftown.lauschig.local.DeviceLibrary.SERVER_ID
        val local = io.wolftown.lauschig.local.DeviceLibrary.titleId("content://tree/x", "dir:a")
        db.itemDao().upsertFromList(listOf(item("i1"), item("i2")))
        progress("i1", last = 10)
        progress("i2", last = 30)
        db.itemDao().upsertDetails(ItemEntity(dev, local, io.wolftown.lauschig.local.DeviceLibrary.LIBRARY_ID, "Momo", durationSec = 100.0))
        db.progressDao().saveLocal(dev, local, 50.0, 100.0, false, 20)
        val s = vm().await { it.continueItems.size == 3 }
        assertEquals(listOf("i2", local, "i1"), s.continueItems.map { it.id })
        val tile = s.continueItems[1]
        assertEquals(dev, tile.serverId)
        assertTrue(tile.isDownloaded)
        assertEquals(0.5f, tile.progress, 0.001f)
    }

    private suspend fun StartViewModel.card(p: (io.wolftown.lauschig.stats.StatsCard?) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { statsCard.first(p) } }

    @Test fun statsCard_appearsOnceSomethingWasHeard_thisDevicesSessionsCountAtOnce() = runTest {
        val vm = vm()
        assertEquals(null, vm.card { true })

        db.sessionDao().upsert(io.wolftown.lauschig.db.entities.LocalSessionEntity("s1", sid, "a", 0.0, 120.0, 120.0, at("2026-11-04"), at("2026-11-04"), synced = false))

        val card = vm.card { it != null }!!
        assertEquals(120.0, card.weekSec, 0.0)
        assertEquals(1, card.streak) // 2 minutes today: today counts, a streak of one day
    }

    @Test fun statsCard_readsTheLastDays_aLongStreakReadsFurtherBack_onceDaysPassTheWeekMovesOn() = runTest {
        // 45 days in a row up to today (the card reads 30 at first), and a session long before.
        val today = java.time.LocalDate.of(2026, 11, 4)
        db.listeningSessionDao().upsertAll(
            (0 until 45).map { i ->
                val day = today.minusDays(i.toLong()).toString()
                io.wolftown.lauschig.db.entities.ListeningSessionEntity(sid, "d$i", "a", "A", null, at(day), at(day) + 60_000, 120.0, 3600.0, 10.0)
            } + io.wolftown.lauschig.db.entities.ListeningSessionEntity(sid, "old", "a", "A", null, at("2025-01-01"), at("2025-01-01"), 120.0, 3600.0, 10.0),
        )
        val vm = vm()

        val card = vm.card { it != null }!!
        assertEquals(45, card.streak)
        assertEquals(3 * 120.0, card.weekSec, 0.0) // Monday to Wednesday

        // Midnight on Sunday: a new week, nothing heard in it yet; the streak still ends yesterday.
        calendar.moveTo(io.wolftown.lauschig.stats.CalendarNow(java.time.LocalDate.of(2026, 11, 9), berlin, java.time.DayOfWeek.MONDAY))
        val monday = vm.card { it != null && it.days.first().start == java.time.LocalDate.of(2026, 11, 9) }!!
        assertEquals(0.0, monday.weekSec, 0.0)
        assertEquals(0, monday.streak) // 5–8 November had nothing: broken
    }
}
