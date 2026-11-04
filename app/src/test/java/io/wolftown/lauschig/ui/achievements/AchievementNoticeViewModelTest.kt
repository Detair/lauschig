package io.wolftown.lauschig.ui.achievements

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import io.wolftown.lauschig.db.dao.AchievementSeenDao
import io.wolftown.lauschig.db.entities.LocalSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.stats.AchievementKey
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.sync.HistoryLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/** First evaluation silent (once the history is complete), then each new unlock once, for the signed-in account only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AchievementNoticeViewModelTest {
    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val history = MutableStateFlow(HistoryLoad.NONE)
    private var progressSynced = true
    private var now = 1_000L
    private val calendar = FakeStatsCalendar(CalendarNow(LocalDate.of(2026, 11, 4), berlin, DayOfWeek.MONDAY))

    private fun at(text: String) = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()
    private fun account(id: String) = Account(id, "u", "user", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
            .allowMainThreadQueries().build()
        accounts.acc.value = account(sid)
    }

    @After fun tearDown() {
        viewModels.clear()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    /** Every evaluation of the history (the expensive step), counted. */
    private val evaluations = AtomicInteger()

    /** Every value the notice had while the Start screen listened. */
    private val notices = CopyOnWriteArrayList<AchievementNotice?>()

    private fun vm(seenDao: AchievementSeenDao = db.achievementSeenDao()) = viewModels.own(
        AchievementNoticeViewModel(
            accounts,
            ListeningDataSource(StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(), Catalog()),
            seenDao,
            historyLoad = { history },
            progressSynced = { progressSynced },
            calendar = calendar,
            clock = { now },
            dispatcher = UnconfinedTestDispatcher(),
            evaluate = { evaluations.incrementAndGet(); it.achievements() },
        ),
    )

    private suspend fun session(id: String, start: String, listened: Double, server: String = sid) =
        db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(server, id, "li_1", "T", "A", at(start), at(start) + 60_000, listened, 360000.0, 10.0)))

    private suspend fun seen(server: String = sid) =
        db.achievementSeenDao().observeAll(server).first().map { "${it.id}:${it.tier}@${it.seenAt}" }.sorted()

    private suspend fun <T> eventually(read: suspend () -> T, p: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(10_000) {
                var v = read()
                while (!p(v)) { delay(10); v = read() }
                v
            }
        }

    private suspend fun AchievementNoticeViewModel.awaitNotice(p: (AchievementNotice?) -> Boolean) =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { notice.first(p) } }

    /** Real time (not the test's virtual clock): the collector runs on real threads. */
    private suspend fun quiet(ms: Long = 300) = withContext(Dispatchers.Default) { delay(ms) }

    /** Waits (in real time) until the history was evaluated at least [n] times, then lets the step after it run. */
    private suspend fun evaluated(n: Int) {
        eventually({ evaluations.get() }) { it >= n }
        quiet()
    }

    @Test fun aHistoryOfYears_isMarkedSeenSilently_onTheFirstEvaluation() = runTest {
        // Two years of listening (1 h, 10 h, a 3-day streak …) arrive with the first sync.
        (0 until 10).forEach { session("s$it", "2024-03-0${it % 9 + 1}T20:00", 4000.0) }
        val vm = vm()
        backgroundScope.launchCollect(vm)

        val rows = eventually({ seen() }) { it.isNotEmpty() }
        assertEquals(listOf("_baseline:0@1000", "hours:1@1000", "hours:2@1000", "streak:1@1000", "streak:2@1000"), rows)
        assertNull(vm.notice.value)
    }

    @Test fun nothingIsMarked_whileTheHistoryIsStillLoading_orTheProgressNeverSynced() = runTest {
        session("1", "2026-11-01T10:00", 4000.0)
        history.value = HistoryLoad.INCOMPLETE
        progressSynced = false
        val vm = vm()
        backgroundScope.launchCollect(vm)
        evaluated(1)
        assertEquals(emptyList<String>(), seen())

        history.value = HistoryLoad.NONE // the history is complete, the progress not yet: still nothing
        quiet()
        assertEquals(emptyList<String>(), seen())

        // More arrives meanwhile; then the progress sync writes and the next look is the first – silent.
        session("2", "2026-11-02T10:00", 40000.0)
        evaluated(2)
        assertEquals(emptyList<String>(), seen())
        progressSynced = true
        // A progress row that changes no tier (no evaluation needed) still settles the account.
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, "li_9", 10.0, 100.0, isFinished = false, lastUpdate = 1)))
        assertEquals(listOf("_baseline:0@1000", "hours:1@1000", "hours:2@1000", "marathon:1@1000"), eventually({ seen() }) { it.isNotEmpty() })
        assertTrue(notices.all { it == null })
    }

    @Test fun aFirstSync_whoseLastPageAndTheCompleteFlagComeBackToBack_tellsNothing() = runTest {
        history.value = HistoryLoad.INCOMPLETE
        val vm = vm()
        backgroundScope.launchCollect(vm)
        evaluated(1) // the empty cache
        // Two years, every three hours: hours, streak, night owl, early bird, weekend, commuter – all in the last page.
        val start = at("2024-11-01T00:00")
        val page = (0 until 5000).map { i ->
            ListeningSessionEntity(sid, "p$i", "li_1", "T", "A", start + i * 3 * 3_600_000L, start + i * 3 * 3_600_000L + 60_000, 1200.0, 360000.0, 10.0)
        }
        db.listeningSessionDao().upsertAll(page)
        history.value = HistoryLoad.NONE // 0 ms later: the flag may be seen before the page's evaluation is done

        eventually({ seen() }) { it.isNotEmpty() }
        evaluated(3) // the empty cache, the page, the fresh look the baseline is taken from – in either order
        val rows = seen()
        assertTrue("the baseline holds the page's tiers: $rows", "hours:7@1000" in rows && "streak:5@1000" in rows && "night_owl:1@1000" in rows)
        assertTrue("told: ${notices.filterNotNull()}", notices.all { it == null })
    }

    @Test fun afterTheBaseline_aNewUnlockIsToldOnce_andSeveralAtOnceTogether() = runTest {
        session("1", "2026-11-01T10:00", 3000.0)
        val vm = vm()
        backgroundScope.launchCollect(vm)
        eventually({ seen() }) { it == listOf("_baseline:0@1000") } // a new listener: nothing reached yet

        session("2", "2026-11-01T12:00", 700.0) // 1 h in total
        val one = vm.awaitNotice { it != null }!!
        assertEquals(AchievementNotice(sid, listOf(AchievementKey("hours", 1))), one)

        now = 2_000L
        vm.shown(one)
        eventually({ seen() }) { "hours:1@2000" in it }
        vm.awaitNotice { it == null }

        // Played on: 10 h, four of them on one day, early in the morning – told together.
        session("3", "2026-11-03T05:00", 33000.0)
        val three = vm.awaitNotice { it != null }!!
        assertEquals(listOf(AchievementKey("hours", 2), AchievementKey("marathon", 1), AchievementKey("early_bird", 1)), three.unlocks)
        vm.shown(three)
        vm.awaitNotice { it == null }
        // Shown is shown: a new look at the same tiers tells nothing again.
        val before = evaluations.get()
        notices.clear()
        session("4", "2026-11-03T12:00", 30.0) // a new session: evaluated again, no new tier
        evaluated(before + 1)
        assertTrue("told again: $notices", notices.all { it == null })
    }

    @Test fun anotherAccount_hasItsOwnBaseline_andALateShownForTheOldOneWritesNothing() = runTest {
        session("1", "2026-11-01T10:00", 3000.0)
        val vm = vm()
        backgroundScope.launchCollect(vm)
        eventually({ seen() }) { it.isNotEmpty() }
        session("2", "2026-11-02T10:00", 700.0)
        val old = vm.awaitNotice { it != null }!!

        accounts.acc.value = account("bbbbbbbbbbbbbbbb")
        session("x", "2026-11-02T10:00", 7200.0, server = "bbbbbbbbbbbbbbbb")
        eventually({ seen("bbbbbbbbbbbbbbbb") }) { it == listOf("_baseline:0@1000", "hours:1@1000") }
        assertNull(vm.awaitNotice { it?.serverId != sid })

        vm.shown(old) // the snackbar of the signed-out account ended late
        quiet()
        assertEquals(listOf("_baseline:0@1000"), seen())
    }

    @Test fun anExistingBaseline_isKept_andATierReachedSinceIsTold() = runTest {
        session("1", "2026-11-01T10:00", 4000.0)
        db.achievementSeenDao().insertAll(listOf(AchievementSeenEntity(sid, AchievementSeenEntity.BASELINE_ID, 0, 5)))
        val vm = vm()
        backgroundScope.launchCollect(vm)
        // The baseline exists (taken before 1 h was reached): 1 h is new and told.
        assertEquals(listOf(AchievementKey("hours", 1)), vm.awaitNotice { it != null }!!.unlocks)
        assertEquals(listOf("_baseline:0@5"), seen())
    }

    @Test fun aPlaybackTickOnACountedDay_isNotEvaluatedAgain_aNewTierIs() = runTest {
        session("1", "2026-11-01T10:00", 1000.0)
        db.sessionDao().upsert(LocalSessionEntity("local", sid, "li_1", 0.0, 100.0, 300.0, at("2026-11-01T12:00"), at("2026-11-01T12:05"), synced = false))
        val vm = vm()
        backgroundScope.launchCollect(vm)
        eventually({ seen() }) { it == listOf("_baseline:0@1000") }
        evaluated(1)
        val before = evaluations.get()

        // Ten more seconds of the open session (310 s, under ten minutes, on a day that counts already): no tier can move.
        db.sessionDao().upsert(LocalSessionEntity("local", sid, "li_1", 0.0, 110.0, 310.0, at("2026-11-01T12:00"), at("2026-11-01T12:05") + 10_000, synced = false))
        quiet(500)
        assertEquals(before, evaluations.get())

        // The open session crosses 1 h in total: evaluated, and told.
        db.sessionDao().upsert(LocalSessionEntity("local", sid, "li_1", 0.0, 2700.0, 2610.0, at("2026-11-01T12:00"), at("2026-11-01T12:50"), synced = false))
        assertEquals(listOf(AchievementKey("hours", 1)), vm.awaitNotice { it != null }!!.unlocks)
        assertEquals(before + 1, evaluations.get())
    }

    @Test fun aFailingWrite_tellsNothing_andTheBaselineIsTriedAgain() = runTest {
        session("1", "2026-11-01T10:00", 4000.0)
        val failures = AtomicInteger(1)
        val flaky = object : AchievementSeenDao by db.achievementSeenDao() {
            override suspend fun insertAll(rows: List<AchievementSeenEntity>) {
                if (failures.getAndDecrement() > 0) throw SQLiteFullException("disk full")
                db.achievementSeenDao().insertAll(rows)
            }
        }
        val vm = vm(flaky)
        backgroundScope.launchCollect(vm)
        evaluated(1)
        assertEquals(emptyList<String>(), seen())
        assertTrue(notices.all { it == null })

        session("2", "2026-11-02T10:00", 60.0) // the next look tries again
        assertEquals(listOf("_baseline:0@1000", "hours:1@1000"), eventually({ seen() }) { it.isNotEmpty() })
        assertTrue(notices.all { it == null })
    }

    @Test fun aFailingRead_endsTheNoticeQuietly_neverTheScreen() = runTest {
        session("1", "2026-11-01T10:00", 4000.0)
        val broken = object : AchievementSeenDao by db.achievementSeenDao() {
            override fun observeAll(serverId: String): Flow<List<AchievementSeenEntity>> = flow { throw SQLiteException("closed") }
        }
        val vm = vm(broken)
        backgroundScope.launchCollect(vm)
        quiet(500)
        assertNull(vm.notice.value)
        // An exception escaping into the view model's scope would fail this test (runTest reports uncaught ones).
    }

    @Test fun mapWhenChanged_computesOnlyForANewKey_andRetriesAFailure() = runTest {
        val computed = mutableListOf<Int>()
        var fail = true
        val out = flowOf(1, 11, 2, 12, 22, 3).mapWhenChanged({ it % 10 }) { v ->
            if (v == 2 && fail) { fail = false; null } else { computed += v; v * 100 }
        }.toList()
        // 11 has the key of 1: no new computation, the last result again; 2 failed (null) and 12 computes again.
        assertEquals(listOf(100, 100, null, 1200, 1200, 300), out)
        assertEquals(listOf(1, 12, 3), computed)
    }

    /** The Start screen listening (the notice is computed only while someone does); every value is kept. */
    private fun CoroutineScope.launchCollect(vm: AchievementNoticeViewModel) =
        launch(Dispatchers.Default.limitedParallelism(1)) { vm.notice.collect { notices += it } }
}
