package io.wolftown.lauschig.ui.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryEntity
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.ReviewSlide
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.sync.HistoryLoad
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The year in review from Room: built once, the history state on the opening slide, covers, a thin or empty year. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class YearReviewViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val history = MutableStateFlow(HistoryLoad.NONE)
    private val calendar = FakeStatsCalendar(CalendarNow(LocalDate.of(2026, 12, 3), berlin, DayOfWeek.MONDAY))

    private fun at(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
        accounts.acc.value = Account(sid, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
    }

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm(year: Int = 2026) = viewModels.own(
        YearReviewViewModel(
            year, accounts,
            ListeningDataSource(StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(), Catalog()),
            calendar, historyLoad = { history },
            covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false },
            dispatcher = UnconfinedTestDispatcher(),
        ),
    )

    private suspend fun YearReviewViewModel.loaded() =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first { !it.loading } } }

    private suspend fun session(id: String, start: String, listened: Double, item: String = "a1") =
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(sid, id, item, "Server $item", "Anna", at(start), at(start) + 60_000, listened, 36000.0, 100.0)))

    @Test fun theYearFromRoom_namesAndNarratorFromTheCatalog_coversOfItsTitles() = runTest {
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(sid, "lib", "Bücher", "book", 0)))
        db.itemDao().upsertFromList(listOf(ItemEntity(sid, "a1", "lib", "Band Eins", author = "Anna", narrator = "Nora", coverUpdatedAt = 42)))
        session("1", "2026-03-02T20:00", 3600.0)
        session("2", "2026-03-03T20:00", 1800.0)
        session("3", "2025-12-31T20:00", 7200.0) // last year
        val state = vm().loaded()

        val review = state.review!!
        assertEquals(2026, review.year)
        assertEquals(ReviewSlide.Opening(2026, 5400.0, 1, incomplete = false), review.slides.first())
        val people = review.slides.filterIsInstance<ReviewSlide.TopPeople>().single()
        assertEquals("Nora", people.narrator?.name)
        // One title: no top-titles slide; the closing names it, from the catalog.
        assertEquals("Band Eins", review.slides.filterIsInstance<ReviewSlide.Closing>().single().topTitle?.title)
        assertEquals(mapOf("a1" to ReviewCover(null, 42)), state.covers)
    }

    @Test fun anIncompleteHistory_isSaidOnTheOpeningSlide() = runTest {
        session("1", "2026-03-02T20:00", 3600.0)
        history.value = HistoryLoad.INCOMPLETE
        val opening = vm().loaded().review!!.slides.first() as ReviewSlide.Opening
        assertTrue(opening.incomplete)
    }

    @Test fun builtOnce_listeningMeanwhileChangesNothingUnderTheReader() = runTest {
        session("1", "2026-03-02T20:00", 3600.0)
        val vm = vm()
        val first = vm.loaded()
        session("2", "2026-03-03T20:00", 7200.0)
        history.value = HistoryLoad.LOADING
        // Room emits again; the story stays as it was opened.
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        assertEquals(first, vm.state.value)
        assertFalse((vm.state.value.review!!.slides.first() as ReviewSlide.Opening).incomplete)
    }

    @Test fun theStorysPlace_staysWithTheViewModel_aNewLengthStartsAgain() = runTest {
        val vm = vm()
        val place = vm.story(9)
        place.next()
        place.next()
        assertTrue(vm.story(9) === place)
        assertEquals(2, vm.story(9).index)
        assertEquals(0, vm.story(8).index)
    }

    @Test fun aYearBelowAnHour_orNoAccount_hasNoReview() = runTest {
        session("1", "2026-03-02T20:00", 3599.0)
        assertNull(vm().loaded().review)
        assertNull(vm(2025).loaded().review)
        accounts.acc.value = null
        val none = vm().loaded()
        assertNull(none.review)
        assertEquals(2026, none.year)
    }
}
