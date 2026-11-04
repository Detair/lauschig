package io.wolftown.lauschig.ui.stats

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
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.sync.HistoryLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StatsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val refreshed = CopyOnWriteArrayList<String>()

    private fun at(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()
    private val now = at("2026-11-04T12:00")

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

    private val calendar = FakeStatsCalendar(CalendarNow(LocalDate.of(2026, 11, 4), berlin, DayOfWeek.MONDAY))
    private val history = MutableStateFlow(HistoryLoad.NONE)

    private val loaded = CopyOnWriteArrayList<String>()

    private fun vm(online: suspend () -> Boolean = { true }, refresh: suspend (String, Boolean) -> Unit = { sid, all -> refreshed += if (all) "$sid:all" else sid }): StatsViewModel =
        viewModels.own(
            StatsViewModel(
                accounts, StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(),
                CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }, Catalog(),
                refresh = refresh, awaitOnline = online, historyLoad = { history }, loadNow = { loaded += it }, calendar = calendar,
                dispatcher = UnconfinedTestDispatcher(),
            ),
        )

    private suspend fun StatsViewModel.await(p: (StatsUiState) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { state.first(p) } }

    private suspend fun session(id: String, start: String, listened: Double, item: String = "li_1", current: Double = 100.0, duration: Double = 3600.0) =
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(sid, id, item, "Server $item", "Autor", at(start), at(start) + 60_000, listened, duration, current)))

    private fun d(text: String) = LocalDate.parse(text)

    @Test fun opensOnTheCurrentWeek_aPeriodSwitchShowsThatPeriodNow() = runTest {
        session("1", "2026-11-03T08:00", 1800.0)
        session("2", "2026-10-15T08:00", 600.0)
        val vm = vm()

        val week = vm.await { it.summary != null }
        assertEquals(StatsPeriod.WEEK, week.period)
        assertEquals(d("2026-11-02"), week.summary!!.range.start)
        assertEquals(1800.0, week.summary.totalSec, 0.0)
        assertEquals(StatsStreakTiles.Current(streak = 1, record = 1), week.tiles) // yesterday counts, today not yet

        vm.setPeriod(StatsPeriod.MONTH)
        val month = vm.await { it.period == StatsPeriod.MONTH }
        assertEquals(d("2026-11-01"), month.summary!!.range.start)
        vm.previous()
        assertEquals(600.0, vm.await { it.summary?.range?.start == d("2026-10-01") }.summary!!.totalSec, 0.0)
        vm.setPeriod(StatsPeriod.YEAR) // a switch starts at the current period again
        assertEquals(2400.0, vm.await { it.period == StatsPeriod.YEAR }.summary!!.totalSec, 0.0)
    }

    @Test fun previousAndNext_stayBetweenTheFirstListeningAndToday() = runTest {
        session("1", "2026-10-20T08:00", 600.0) // two weeks back (the week of 19 October)
        val vm = vm()

        val now = vm.await { it.summary != null }
        assertFalse(now.hasNext)
        assertTrue(now.hasPrevious)
        // Not into the future: had next() moved on to 9 November, one previous() would land on 2 November.
        vm.next()
        vm.previous()
        assertEquals(d("2026-10-26"), vm.await { it.summary?.range?.start != d("2026-11-02") }.summary!!.range.start)

        vm.previous()
        val first = vm.await { it.summary?.range?.start == d("2026-10-19") }
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertTrue(first.tiles is StatsStreakTiles.Past)
        // Nothing before the first listening: had previous() gone to 12 October, one next() would land on 19 October.
        vm.previous()
        vm.next()
        assertEquals(d("2026-10-26"), vm.await { it.summary?.range?.start != d("2026-10-19") }.summary!!.range.start)
        vm.next()
        val back = vm.await { it.summary?.range?.start == d("2026-11-02") }
        assertTrue(back.tiles is StatsStreakTiles.Current)
        assertFalse(back.hasNext)
    }

    @Test fun refreshOnOpen_onlyOnceTheConnectionCheckSaysOnline() = runTest {
        val offline = vm(online = { false })
        assertFalse(offline.await { !it.refreshing }.refreshing)
        assertEquals(emptyList<String>(), refreshed)

        val online = vm(online = { true })
        online.await { !it.refreshing }
        assertEquals(listOf(sid), refreshed)
    }

    @Test fun emptyCache_offline_settlesOnTheEmptyState_notAnEndlessSpinner() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val vm = vm(online = { gate.await() })

        val waiting = vm.await { !it.loading }
        assertTrue(waiting.refreshing) // the connection check has not answered: the page shows it is loading
        assertFalse(waiting.hasData)
        gate.complete(false)
        val settled = vm.await { !it.refreshing }
        assertFalse(settled.hasData)
        assertTrue(settled.unreached)
    }

    @Test fun aFailedRefresh_keepsTheCache_andSaysTheServerWasNotReached() = runTest {
        session("1", "2026-11-03T08:00", 1800.0)
        val vm = vm(refresh = { _, _ -> throw java.io.IOException("down") })

        val s = vm.await { !it.refreshing && it.summary != null }
        assertTrue(s.unreached)
        assertEquals(1800.0, s.summary!!.totalSec, 0.0)
    }

    @Test fun thisDevicesSessions_count_namesComeFromTheCatalog_seriesOnlyInAll() = runTest {
        db.libraryDao().upsertFromServer(listOf(LibraryEntity(sid, "lib", "Bücher", "book", 0)))
        db.itemDao().upsertFromList(
            listOf(
                ItemEntity(sid, "a1", "lib", "Band Eins", author = "Anna", seriesName = "Reihe", seriesSortKey = 1.0, durationSec = 3600.0),
                ItemEntity(sid, "a2", "lib", "Band Zwei", author = "Anna", seriesName = "Reihe", seriesSortKey = 2.0, durationSec = 3600.0),
            ),
        )
        session("1", "2026-11-03T08:00", 1800.0, item = "a1", current = 3600.0)
        // Heard on this device, not uploaded yet: it counts, named from the catalog.
        db.sessionDao().upsert(LocalSessionEntity("local", sid, "a2", 0.0, 3600.0, 900.0, at("2026-11-04T09:00"), at("2026-11-04T09:15"), synced = false))
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, "a2", 3600.0, 3600.0, isFinished = true, lastUpdate = 1)))
        val vm = vm()

        val week = vm.await { it.summary?.totalSec == 2700.0 }
        assertEquals(listOf("Band Eins", "Band Zwei"), week.topTitles.map { it.title })
        assertEquals(listOf("Band Zwei", "Band Eins"), week.finished.map { it.title }) // newest first
        assertTrue(week.topTitles.all { it.known })
        assertEquals(emptyList<Any>(), week.finishedSeries)
        assertEquals(StatsStreakTiles.Current(streak = 2, record = 2), week.tiles) // yesterday and today

        vm.setPeriod(StatsPeriod.ALL)
        val all = vm.await { it.period == StatsPeriod.ALL }
        assertEquals(listOf("Reihe"), all.finishedSeries.map { it.name })
        assertFalse(all.hasPrevious)
        assertFalse(all.hasNext)
    }

    @Test fun theAchievementsPreview_theLatestUnlocks_andTheCount_whateverThePeriod() = runTest {
        session("1", "2026-10-01T20:00", 3600.0) // 1 h on 1 October
        session("2", "2026-10-05T02:00", 900.0)  // a night owl on 5 October
        val vm = vm()

        val week = vm.await { it.achievements != null }
        assertEquals(listOf(Achievement.NIGHT_OWL, Achievement.HOURS), week.achievements!!.recent.map { it.achievement })
        assertEquals(2, week.achievements.unlockedTiers)
        assertEquals(28, week.achievements.totalTiers)
        vm.setPeriod(StatsPeriod.YEAR)
        assertEquals(week.achievements, vm.await { it.period == StatsPeriod.YEAR }.achievements)
    }

    @Test fun aPastPeriod_showsItsLongestRunAndItsDays_neverTodaysStreak() = runTest {
        // Last week: Tuesday to Thursday in a row, and Saturday; this week: yesterday and today.
        for ((i, day) in listOf("2026-10-27", "2026-10-28", "2026-10-29", "2026-10-31", "2026-11-03", "2026-11-04").withIndex()) {
            session("s$i", "${day}T08:00", 600.0)
        }
        val vm = vm()
        assertEquals(StatsStreakTiles.Current(streak = 2, record = 3), vm.await { it.summary != null }.tiles)

        vm.previous()

        assertEquals(StatsStreakTiles.Past(longest = 3, activeDays = 4), vm.await { it.summary?.range?.start == d("2026-10-26") }.tiles)
    }

    @Test fun atMidnight_orOnResume_todayIsReadAgain() = runTest {
        session("1", "2026-11-08T22:00", 600.0) // Sunday evening
        calendar.current = CalendarNow(d("2026-11-08"), berlin, DayOfWeek.MONDAY)
        val vm = vm()
        assertEquals(d("2026-11-02"), vm.await { it.summary != null }.summary!!.range.start)

        calendar.moveTo(CalendarNow(d("2026-11-09"), berlin, DayOfWeek.MONDAY)) // midnight: a new week
        val monday = vm.await { it.summary?.range?.start == d("2026-11-09") }
        assertEquals(0.0, monday.summary!!.totalSec, 0.0)
        assertTrue(monday.hasPrevious)

        // The week start changed in the system settings while the page was away: read again on resume.
        calendar.current = CalendarNow(d("2026-11-09"), berlin, DayOfWeek.SUNDAY)
        vm.onResume()
        assertEquals(d("2026-11-08"), vm.await { it.summary?.range?.start == d("2026-11-08") }.summary!!.range.start)
    }

    @Test fun theRefresh_belongsToTheAccount_signOutOrAnotherAccountCancelsIt() = runTest {
        val started = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val vm = vm(refresh = { sid, _ ->
            started += sid
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                cancelled += sid
            }
        })
        vm.await { it.refreshing }
        withTimeout(10_000) { while (started.isEmpty()) kotlinx.coroutines.delay(5) }

        val other = "bbbbbbbbbbbbbbbb"
        accounts.acc.value = Account(other, "v", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        withTimeout(10_000) { while (started.size < 2) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(sid), cancelled)
        assertEquals(listOf(sid, other), started)

        accounts.acc.value = null // signed out
        withTimeout(10_000) { while (cancelled.size < 2) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(sid, other), cancelled)
        assertFalse(vm.await { !it.refreshing }.refreshing)
    }

    @Test fun theHistoryLoad_reachesThePage() = runTest {
        session("1", "2026-11-03T08:00", 1800.0)
        val vm = vm()
        assertEquals(HistoryLoad.NONE, vm.await { it.summary != null }.history)

        history.value = HistoryLoad.LOADING
        vm.await { it.history == HistoryLoad.LOADING }
        history.value = HistoryLoad.INCOMPLETE
        vm.await { it.history == HistoryLoad.INCOMPLETE }
    }

    @Test fun loadNow_isHandedToTheSync_forThisAccount_evenBeforeTheFirstRefreshEnded() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val vm = vm(online = { gate.await() }) // the connection check has not answered yet
        vm.await { it.refreshing }

        vm.loadHistoryNow() // an early tap is not dropped

        withTimeout(10_000) { while (loaded.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(listOf(sid), loaded)
        gate.complete(true)
    }

    @Test fun thePage_listensToTheCalendarOnce() = runTest {
        session("1", "2026-11-03T08:00", 1800.0)
        val vm = vm()
        // The page is on screen: its state stays collected (the VM stops listening 5 s after the last collector – a
        // bare await would race that stop against the asynchronous subscription).
        val onScreen = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.await { it.summary != null }

        // Wait for the subscription, give a second listener the same chance to appear, then there must be exactly one.
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) { calendar.listenerCount.first { it >= 1 } }
            kotlinx.coroutines.delay(200)
        }
        assertEquals(1, calendar.listeners)
        onScreen.cancel()
    }
}
