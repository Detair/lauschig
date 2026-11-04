package io.wolftown.lauschig.ui.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.sync.InMemorySyncStamps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The Start card: December and January only, from an hour in that year, hidden per account and year (fixed clock). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class YearReviewCardViewModelTest {
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val stamps = InMemorySyncStamps()

    private fun day(text: String) = CalendarNow(LocalDate.parse(text), berlin, DayOfWeek.MONDAY)
    private val calendar = FakeStatsCalendar(day("2026-12-03"))

    private fun at(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
        account(sid)
    }

    private fun account(id: String?) {
        accounts.acc.value = id?.let { Account(it, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet()) }
    }

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private fun vm() = viewModels.own(
        YearReviewCardViewModel(accounts, StatsSessions(db.listeningSessionDao(), db.sessionDao()), calendar, stamps, { 7L }, UnconfinedTestDispatcher()),
    )

    /** The offered year once [p] holds for the offer (the data read: no sleeping to prove a "none"). */
    private suspend fun YearReviewCardViewModel.await(p: (YearReviewOffer) -> Boolean = { it.known }): Int? =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { card.first { it.known && p(it) } } }.year

    private suspend fun session(id: String, start: String, listened: Double) =
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(sid, id, "li_1", "T", "A", at(start), at(start) + 60_000, listened, 36000.0, 10.0)))

    @Test fun fromDecemberToJanuary_theYearThatEnds_withAnHourInIt() = runTest {
        session("1", "2026-05-01T10:00", 3600.0)
        calendar.current = day("2026-11-30")
        val vm = vm()
        assertNull(vm.await())
        calendar.moveTo(day("2026-12-01"))
        assertEquals(2026, vm.await { it.year != null })
        calendar.moveTo(day("2027-01-31"))
        assertEquals(2026, vm.await { it.year == 2026 })
        calendar.moveTo(day("2027-02-01"))
        assertNull(vm.await { it.year == null })
        // In December 2027 the year 2027: nothing until it has an hour.
        calendar.moveTo(day("2027-12-01"))
        assertNull(vm.await())
        session("2", "2027-06-01T10:00", 3600.0)
        assertEquals(2027, vm.await { it.year != null })
    }

    @Test fun belowAnHour_noCard_untilTheHourIsFull_alsoWithThisDevicesOwnSessions() = runTest {
        session("1", "2026-05-01T10:00", 3000.0)
        // January's listening belongs to the new year: it does not fill 2026's hour.
        session("2", "2027-01-02T10:00", 7200.0)
        calendar.current = day("2027-01-05")
        val vm = vm()
        assertNull(vm.await())
        db.sessionDao().upsert(LocalSessionEntity("local", sid, "li_1", 0.0, 600.0, 600.0, at("2026-12-30T21:00"), at("2026-12-30T21:10"), synced = false))
        assertEquals(2026, vm.await { it.year != null })
    }

    @Test fun hidden_goesAtOnce_staysHiddenForThatYear_andComesBackAfterASignOutWithoutKeeping() = runTest {
        session("1", "2026-05-01T10:00", 3600.0)
        val vm = vm()
        assertEquals(2026, vm.await { it.year != null })
        vm.hide(2026)
        assertNull(vm.await { it.year == null })
        assertNotNull(stamps.get(YearReviewCardViewModel.hiddenKey(2026, sid)))
        assertEquals("year_review_hidden_2026:$sid", YearReviewCardViewModel.hiddenKey(2026, sid))
        // A new screen (an app start) remembers it.
        assertNull(vm().await())
        // Another account on this device has its own card.
        account("bbbbbbbbbbbbbbbb")
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity("bbbbbbbbbbbbbbbb", "9", "li_1", "T", "A", at("2026-06-01T10:00"), at("2026-06-01T11:00"), 3600.0, 36000.0, 10.0)))
        assertEquals(2026, vm().await { it.year != null })
        // The sign-out without keeping removes the account's stamps: offered again.
        account(sid)
        stamps.removeServer(sid)
        assertEquals(2026, vm().await { it.year != null })
    }

    @Test fun withoutAccount_noCard() = runTest {
        account(null)
        assertNull(vm().await())
    }
}
