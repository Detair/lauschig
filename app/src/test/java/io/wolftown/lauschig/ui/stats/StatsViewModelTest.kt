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
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.CompletableDeferred
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

    private fun vm(online: suspend () -> Boolean = { true }, refresh: suspend (String) -> Unit = { refreshed += it }): StatsViewModel =
        viewModels.own(
            StatsViewModel(
                accounts, StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(),
                CoverPathCache(DownloadFiles(tmp.newFolder("dl${System.nanoTime()}"), db.audioFileDao())) { false }, Catalog(),
                refresh = refresh, awaitOnline = online, clock = { now }, zone = { berlin }, weekStart = { DayOfWeek.MONDAY },
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
        assertTrue(week.isCurrent)

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
        vm.next() // not into the future
        assertEquals(d("2026-11-02"), vm.await { true }.summary!!.range.start)

        vm.previous()
        vm.previous()
        val first = vm.await { it.summary?.range?.start == d("2026-10-19") }
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertFalse(first.isCurrent)
        vm.previous() // nothing before the first listening
        assertEquals(d("2026-10-19"), vm.await { true }.summary!!.range.start)
        vm.next()
        vm.next()
        val back = vm.await { it.summary?.range?.start == d("2026-11-02") }
        assertTrue(back.isCurrent)
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
        val vm = vm(refresh = { throw java.io.IOException("down") })

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
        assertEquals(2, week.streaks.current) // yesterday and today

        vm.setPeriod(StatsPeriod.ALL)
        val all = vm.await { it.period == StatsPeriod.ALL }
        assertEquals(listOf("Reihe"), all.finishedSeries.map { it.name })
        assertFalse(all.hasPrevious)
        assertFalse(all.hasNext)
    }
}
