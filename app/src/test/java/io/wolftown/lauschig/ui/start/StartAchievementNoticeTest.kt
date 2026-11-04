package io.wolftown.lauschig.ui.start

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import io.wolftown.lauschig.db.entities.ListeningSessionEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.FakeStatsCalendar
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.achievements.AchievementNoticeViewModel
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
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

private class IdlePlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    override fun play(serverId: String, itemId: String, startSec: Double?) {}
    override fun togglePlayPause() {}
    override fun pause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) {}
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

/** Start as the app wires it: the notice view model, the app's snackbar host and „Ansehen“ to the achievements. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartAchievementNoticeTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: LauschigDatabase
    private val sid = "aaaaaaaaaaaaaaaa"
    private val berlin = ZoneId.of("Europe/Berlin")
    private val viewModels = TestViewModels()
    private val accounts = FakeAccountStore()
    private val calendar = FakeStatsCalendar(CalendarNow(LocalDate.of(2026, 11, 4), berlin, DayOfWeek.MONDAY))

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

    @Test fun aNewAchievement_showsInTheAppsHost_andViewOpensTheAchievements() {
        runBlocking {
            db.achievementSeenDao().insertAll(listOf(AchievementSeenEntity(sid, AchievementSeenEntity.BASELINE_ID, 0, 1)))
            db.listeningSessionDao().upsertAll(listOf(ListeningSessionEntity(sid, "1", "li_1", "T", "A", at("2026-11-02T10:00"), at("2026-11-02T11:00"), 3700.0, 36000.0, 10.0)))
        }
        val covers = CoverPathCache(DownloadFiles(tmp.newFolder("dl"), db.audioFileDao())) { false }
        val start = viewModels.own(
            StartViewModel(
                covers, accounts, db.catalogDao(), db.libraryDao(), db.itemDao(), db.progressDao(), db.downloadDao(), db.episodeDao(), IdlePlayer(), Catalog(),
                StatsSessions(db.listeningSessionDao(), db.sessionDao()), calendar, UnconfinedTestDispatcher(),
            ),
        )
        val notices = viewModels.own(
            AchievementNoticeViewModel(
                accounts,
                ListeningDataSource(StatsSessions(db.listeningSessionDao(), db.sessionDao()), db.progressDao(), db.catalogDao(), db.libraryDao(), Catalog()),
                db.achievementSeenDao(), historyLoad = { flowOf(HistoryLoad.NONE) }, progressSynced = { true }, calendar = calendar,
                clock = { 2_000L }, dispatcher = UnconfinedTestDispatcher(),
            ),
        )
        var opened = 0
        rule.setContent {
            LauschigTheme {
                val host = androidx.compose.runtime.remember { SnackbarHostState() }
                Box {
                    StartRoute(onOpenItem = {}, snackbarHost = host, onOpenAchievements = { opened++ }, viewModel = start, notices = notices)
                    SnackbarHost(host)
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("New achievement: 1 hour listened").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("View").performClick()
        rule.waitUntil(10_000) { opened == 1 }
        rule.waitUntil(10_000) { runBlocking { db.achievementSeenDao().observeAll(sid).first() }.any { it.id == "hours" && it.tier == 1 } }
        assertEquals(1, opened)
    }
}
