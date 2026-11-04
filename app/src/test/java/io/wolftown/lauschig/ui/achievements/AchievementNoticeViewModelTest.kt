package io.wolftown.lauschig.ui.achievements

import android.content.Context
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

    private fun vm() = viewModels.own(
        AchievementNoticeViewModel(
            accounts,
            ListeningDataSource(StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(), Catalog()),
            db.achievementSeenDao(),
            historyLoad = { history },
            progressSynced = { progressSynced },
            calendar = calendar,
            clock = { now },
            dispatcher = UnconfinedTestDispatcher(),
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
        delay(200)
        assertEquals(emptyList<String>(), seen())

        history.value = HistoryLoad.NONE // the history is complete, the progress not yet: still nothing
        delay(200)
        assertEquals(emptyList<String>(), seen())

        // More arrives meanwhile; then the progress sync writes and the next evaluation is the first – silent.
        session("2", "2026-11-02T10:00", 40000.0)
        delay(200)
        assertEquals(emptyList<String>(), seen())
        progressSynced = true
        db.progressDao().mergeFromServer(listOf(ProgressEntity(sid, "li_9", 10.0, 100.0, isFinished = false, lastUpdate = 1)))
        assertEquals(listOf("_baseline:0@1000", "hours:1@1000", "hours:2@1000", "marathon:1@1000"), eventually({ seen() }) { it.isNotEmpty() })
        assertNull(vm.notice.value)
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
        // Shown is shown: the same data never tells it again.
        session("1", "2026-11-01T10:00", 3001.0)
        delay(200)
        assertNull(vm.notice.value)
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
        delay(200)
        assertEquals(listOf("_baseline:0@1000"), seen())
    }

    @Test fun aSecondScreen_doesNotTakeTheBaselineTwice() = runTest {
        session("1", "2026-11-01T10:00", 4000.0)
        db.achievementSeenDao().insertAll(listOf(AchievementSeenEntity(sid, AchievementSeenEntity.BASELINE_ID, 0, 5)))
        val vm = vm()
        backgroundScope.launchCollect(vm)
        // The baseline exists (taken before 1 h was reached): 1 h is new and told.
        assertEquals(listOf(AchievementKey("hours", 1)), vm.awaitNotice { it != null }!!.unlocks)
        assertEquals(listOf("_baseline:0@5"), seen())
    }

    /** The Start screen listening (the notice is computed only while someone does). */
    private fun CoroutineScope.launchCollect(vm: AchievementNoticeViewModel) =
        launch(Dispatchers.Default.limitedParallelism(1)) { vm.notice.collect {} }
}
