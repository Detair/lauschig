package io.wolftown.lauschig.ui.achievements

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.StatsSessions
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AchievementsViewModelTest {
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val calendar = FakeStatsCalendar(CalendarNow(LocalDate.of(2026, 11, 4), berlin, DayOfWeek.MONDAY))

    private fun at(text: String, zone: ZoneId = berlin) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

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

    private fun vm() = viewModels.own(
        AchievementsViewModel(
            accounts,
            ListeningDataSource(StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(), Catalog()),
            calendar, UnconfinedTestDispatcher(),
        ),
    )

    private suspend fun session(id: String, startMs: Long, listened: Double) =
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(sid, id, "li_1", "T", "A", startMs, startMs + 60_000, listened, 360000.0, 10.0)))

    private suspend fun AchievementsViewModel.await(p: (AchievementsUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    @Test fun everyAchievement_recentlyUnlockedFirst_andTheCount() = runTest {
        session("1", at("2026-10-01T20:00"), 3600.0)  // 1 h on 1 October
        session("2", at("2026-10-05T02:00"), 900.0)   // night owl on 5 October
        val state = vm().await { !it.loading && it.unlockedTiers > 0 }

        assertEquals(Achievement.entries.size, state.cards.size)
        assertEquals(listOf(Achievement.NIGHT_OWL, Achievement.HOURS), state.cards.take(2).map { it.achievement })
        assertEquals(2, state.unlockedTiers)
        assertEquals(28, state.totalTiers)
    }

    @Test fun aZoneChange_readsTheTimeWindowsAgain() = runTest {
        // 01:30 in Berlin is 19:30 the evening before in New York.
        session("1", at("2026-10-05T01:30"), 900.0)
        val vm = vm()
        assertEquals(1, vm.await { !it.loading && it.unlockedTiers > 0 }.unlockedTiers)
        calendar.moveTo(CalendarNow(LocalDate.of(2026, 11, 4), ZoneId.of("America/New_York"), DayOfWeek.SUNDAY))
        vm.await { it.unlockedTiers == 0 }
    }

    @Test fun signedOut_allLocked_notLoading() = runTest {
        accounts.acc.value = null
        val state = vm().await { !it.loading }
        assertEquals(0, state.unlockedTiers)
        assertEquals(Achievement.entries.size, state.cards.size)
    }
}
