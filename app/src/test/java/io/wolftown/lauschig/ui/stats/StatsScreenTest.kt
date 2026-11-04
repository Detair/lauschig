package io.wolftown.lauschig.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.AchievementState
import io.wolftown.lauschig.stats.AchievementsPreview
import io.wolftown.lauschig.stats.AuthorTime
import io.wolftown.lauschig.stats.Bar
import io.wolftown.lauschig.stats.BarUnit
import io.wolftown.lauschig.stats.FinishedSeries
import io.wolftown.lauschig.stats.PeriodRange
import io.wolftown.lauschig.stats.PeriodSummary
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val monday = LocalDate.of(2026, 11, 2)

    private fun week(seconds: List<Double>, today: Int? = 2): PeriodSummary {
        val range = PeriodRange(StatsPeriod.WEEK, monday, monday.plusDays(7))
        val bars = seconds.mapIndexed { i, s -> Bar(monday.plusDays(i.toLong()), s, i == today) }
        return PeriodSummary(range, seconds.sum(), seconds.count { it >= 60 }, BarUnit.DAY, bars, emptyList(), emptyList(), emptyList())
    }

    private val listened = StatsUiState(
        loading = false, hasData = true, period = StatsPeriod.WEEK,
        summary = week(listOf(2400.0, 0.0, 30.0, 0.0, 0.0, 0.0, 0.0)),
        hasPrevious = true, hasNext = false,
        tiles = StatsStreakTiles.Current(streak = 1, record = 5),
        today = LocalDate.of(2026, 11, 4),
        finished = listOf(StatsTitle("li_b", "Book B", "Ben", day = monday, known = true)),
        topTitles = listOf(StatsTitle("li_a", "Book A", "Anna", seconds = 2400.0, known = true), StatsTitle("gone", null, null, seconds = 30.0)),
        topAuthors = listOf(AuthorTime("Anna", 2400.0)),
    )

    @Test fun noListening_showsTheEmptyState_loadingWhileTheServerIsAsked() {
        var state by mutableStateOf(StatsUiState(loading = false, refreshing = true))
        rule.setContent { LauschigTheme { StatsScreen(state, onBack = {}) } }
        rule.onNodeWithTag("loadingState").assertExists()
        rule.onNodeWithText("Nothing listened yet").assertDoesNotExist()

        state = StatsUiState(loading = false, refreshing = false)
        rule.onNodeWithText("Nothing listened yet").assertIsDisplayed()
        rule.onNodeWithText("Once you listen, you will see here how much – also offline.").assertIsDisplayed()

        state = StatsUiState(loading = false, refreshing = false, unreached = true)
        rule.onNodeWithText("The server could not be reached. Listening on other devices shows up once it is.").assertIsDisplayed()
    }

    @Test fun aWeek_showsItsTotal_chartValues_tiles_andTitles() {
        val calls = mutableListOf<String>()
        rule.setContent {
            LauschigTheme {
                StatsScreen(
                    listened, onBack = {}, onPeriod = { calls += "period:$it" }, onPrevious = { calls += "previous" }, onNext = { calls += "next" },
                    onOpenItem = { calls += "open:$it" },
                )
            }
        }
        rule.onNodeWithTag("period:WEEK").assertIsSelected()
        rule.onNodeWithTag("statsTotal").assertTextEquals("40 min")
        rule.onNodeWithTag("statsActiveDays").assertTextEquals("on 1 day")
        rule.onNodeWithTag("statsChart").assertContentDescriptionEquals(
            "Listening time per day: Monday 40 min, Tuesday 0 min, Wednesday (today) 30 s, Thursday 0 min, Friday 0 min, Saturday 0 min, Sunday 0 min",
        )
        rule.onNodeWithTag("tileStreak").assertTextEquals("Streak", "1 day")
        rule.onNodeWithTag("tileLongest").assertTextEquals("Best streak", "5 days")
        rule.onNodeWithTag("tileFinished").assertTextEquals("Finished", "1 book")
        rule.onNodeWithTag("statsNext").assertIsNotEnabled()
        rule.onNodeWithTag("statsPrevious").assertIsEnabled().performClick()
        rule.onNodeWithTag("period:YEAR").performClick()

        val list = rule.onNodeWithTag("statsList")
        list.performScrollToNode(hasText("Finished books"))
        rule.onNodeWithText("Book B").assertIsDisplayed()
        rule.onNodeWithText("Nov 2").assertIsDisplayed() // this year: no year
        list.performScrollToNode(hasText("Most listened"))
        list.performScrollToNode(hasText("Book A"))
        rule.onNodeWithText("Book A").performClick()
        list.performScrollToNode(hasText("Unknown title"))
        rule.onNodeWithText("Unknown title").performClick() // not in the catalog: nothing to open
        list.performScrollToNode(hasText("Authors"))
        rule.onAllNodesWithText("Anna", useUnmergedTree = true).assertCountEquals(2) // Book A's author and the author row
        assertEquals(listOf("previous", "period:YEAR", "open:li_a"), calls)
    }

    @Test fun achievements_theThreeLatest_andAllWithTheCount() {
        val calls = mutableListOf<String>()
        val preview = AchievementsPreview(
            recent = listOf(
                AchievementState(Achievement.HOURS, listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 3)), 11 * 3600.0),
                AchievementState(Achievement.NIGHT_OWL, listOf(LocalDate.of(2026, 10, 5)), null),
                AchievementState(Achievement.BOOKS, listOf(null), 1.0),
            ),
            unlockedTiers = 4, totalTiers = 28,
        )
        rule.setContent { LauschigTheme { StatsScreen(listened.copy(achievements = preview), onBack = {}, onOpenAchievements = { calls += "all" }) } }
        val list = rule.onNodeWithTag("statsList")
        list.performScrollToNode(hasText("Achievements"))
        rule.onNodeWithTag("statsAchievementsAll").assertTextEquals("All (4/28)").assertContentDescriptionEquals("All achievements, reached: 4 of 28")
        list.performScrollToNode(hasText("10 hours listened"))
        rule.onNodeWithText("Since November 3, 2026").assertIsDisplayed()
        list.performScrollToNode(hasText("Night owl"))
        list.performScrollToNode(hasText("1 book finished"))
        rule.onNodeWithText("Reached").assertIsDisplayed()
        // TalkBack: a button that says what it does.
        rule.onNode(hasText("Night owl"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher("click label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "View achievement" })
        rule.onNodeWithText("Night owl").performClick()
        rule.onNodeWithTag("statsAchievementsAll").performClick()
        assertEquals(listOf("all", "all"), calls)
    }

    @Test fun achievements_noneYet_saysSo_allStillOpensThem() {
        rule.setContent {
            LauschigTheme { StatsScreen(listened.copy(achievements = AchievementsPreview(emptyList(), 0, 28)), onBack = {}) }
        }
        rule.onNodeWithTag("statsList").performScrollToNode(hasText("No achievements yet"))
        rule.onNodeWithTag("statsAchievementsAll").assertTextEquals("All (0/28)")
    }

    @Test fun anEmptyPeriod_saysSo_andShowsNoLists() {
        val empty = listened.copy(
            summary = week(List(7) { 0.0 }, today = null), tiles = StatsStreakTiles.Past(longest = 0, activeDays = 0), hasNext = true,
            finished = emptyList(), topTitles = emptyList(), topAuthors = emptyList(),
        )
        rule.setContent { LauschigTheme { StatsScreen(empty, onBack = {}) } }
        rule.onNodeWithTag("statsTotal").assertTextEquals("0 min")
        rule.onNodeWithTag("statsActiveDays").assertTextEquals("Nothing listened in this period")
        rule.onNodeWithTag("statsChart").assertContentDescriptionEquals("No listening time")
        // A past period: its own longest run and days, not today's streak.
        rule.onNodeWithTag("tileStreak").assertTextEquals("Longest streak", "0 days")
        rule.onNodeWithTag("tileLongest").assertTextEquals("Days listened", "0 days")
        rule.onNodeWithTag("statsNext").assertIsEnabled()
        rule.onNodeWithText("Most listened").assertDoesNotExist()
        rule.onNodeWithText("Finished books").assertDoesNotExist()
    }

    @Test fun allTime_hasNoPreviousOrNext_andCountsTheFinishedSeries() {
        val range = PeriodRange(StatsPeriod.ALL, LocalDate.of(2025, 3, 1), LocalDate.of(2026, 11, 5))
        val all = listened.copy(
            period = StatsPeriod.ALL,
            summary = PeriodSummary(range, 7200.0, 3, BarUnit.YEAR, listOf(Bar(LocalDate.of(2025, 1, 1), 3600.0, false), Bar(LocalDate.of(2026, 1, 1), 3600.0, true)), emptyList(), emptyList(), emptyList()),
            // The same name in two libraries (Hörbuch and Hörspiel) is two series: two rows, no key clash.
            finishedSeries = listOf(FinishedSeries("Reihe", 3, "a1", libraryId = "books"), FinishedSeries("Reihe", 2, "b1", libraryId = "plays")),
        )
        rule.setContent { LauschigTheme { StatsScreen(all, onBack = {}) } }
        rule.onNodeWithTag("statsPrevious").assertDoesNotExist()
        rule.onNodeWithTag("statsNext").assertDoesNotExist()
        rule.onNodeWithTag("statsPeriodLabel").assertTextEquals("Since Mar 1, 2025")
        rule.onNodeWithTag("statsChart").assertContentDescriptionEquals("Listening time per year: 2025 1 h 0 min, 2026 (this year) 1 h 0 min")
        rule.onNodeWithTag("tileFinished").assertTextEquals("Finished", "1 book", "2 series")
        rule.onNodeWithTag("statsList").performScrollToNode(hasText("Book B"))
        rule.onNodeWithText("Nov 2, 2026").assertIsDisplayed() // all time: always with the year
        rule.onNodeWithTag("statsList").performScrollToNode(hasText("3 volumes"))
        rule.onNodeWithText("3 volumes").assertIsDisplayed()
        rule.onNodeWithTag("statsList").performScrollToNode(hasText("2 volumes"))
        rule.onNodeWithText("2 volumes").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h1600dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tiles_shareOneLine_andAreNoTallerThanTheirText() {
        rule.setContent { LauschigTheme { StatsScreen(listened, onBack = {}) } }
        val tiles = listOf("tileStreak", "tileLongest", "tileFinished").map { tag ->
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            val texts = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasTextExactly(), useUnmergedTree = true).fetchSemanticsNodes()
            val textBottom = texts.maxOf { it.positionInRoot.y + it.size.height }
            // Below the last line only the tile's own padding (12 dp; 1 px = 1 dp here).
            assertTrue("$tag: ${node.positionInRoot.y + node.size.height - textBottom} px below its text", node.positionInRoot.y + node.size.height - textBottom <= 12.5f)
            node.positionInRoot.y
        }
        assertEquals(1, tiles.distinct().size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring
    fun atDoubleFontSize_nothingIsCutOff() {
        rule.setContent { LauschigTheme { StatsScreen(listened, onBack = {}) } }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val total = rule.onNodeWithTag("statsTotal").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("total $total outside $root", total.left >= root.left && total.right <= root.right)
        // Every tile holds its whole text: the tiles grow with it instead of clipping.
        for (tag in listOf("tileStreak", "tileLongest", "tileFinished")) {
            val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
            // Unclipped bounds (the list may cut a tile off at the bottom of the screen; that is not what this checks).
            val tile = Rect(node.positionInRoot, node.size.toSize())
            val texts = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasTextExactly(), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(tag, 2, texts.size)
            texts.forEach { t ->
                // Unclipped: where the text is laid out, not what its parents let show.
                val top = t.positionInRoot.y
                val right = t.positionInRoot.x + t.size.width
                assertTrue("$tag: text at $top..${top + t.size.height} / $right outside $tile", top >= tile.top - 0.5f && top + t.size.height <= tile.bottom + 0.5f && right <= tile.right + 0.5f)
                val layouts = mutableListOf<TextLayoutResult>()
                t.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                assertEquals("$tag: ${t.config[SemanticsProperties.Text]} breaks", 1, layouts.single().lineCount)
            }
        }
        StatsPeriod.entries.forEach { rule.onNodeWithTag("period:$it").assertIsDisplayed() }
    }

    /** A node that shows text (a Text, not a container). */
    private fun hasTextExactly() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    @Test fun whileTheHistoryLoads_orWaitsForWifi_aQuietLineSaysSo() {
        var state by mutableStateOf(listened.copy(history = HistoryLoad.LOADING))
        var loadNow = 0
        rule.setContent { LauschigTheme { StatsScreen(state, onBack = {}, onLoadNow = { loadNow++ }) } }
        rule.onNodeWithTag("statsHistory").assertTextEquals("Loading your listening history …")
        rule.onNodeWithTag("statsLoadNow").assertDoesNotExist() // already loading
        rule.onNodeWithTag("statsTotal").assertIsDisplayed() // the numbers stay usable

        state = listened.copy(history = HistoryLoad.INCOMPLETE)
        rule.onNodeWithTag("statsHistory").assertTextEquals("Your full listening history loads on Wi-Fi.")
        rule.onNodeWithTag("statsLoadNow").assertTextEquals("Load now").performClick()
        assertEquals(1, loadNow)

        state = listened.copy(history = HistoryLoad.FAILED) // the last read failed: not "loads on Wi-Fi"
        rule.onNodeWithTag("statsHistory").assertTextEquals("Listening history incomplete – load again")
        rule.onNodeWithTag("statsLoadNow").assertTextEquals("Load again").performClick()
        assertEquals(2, loadNow)

        state = listened
        rule.onNodeWithTag("statsHistory").assertDoesNotExist()

        state = StatsUiState(loading = false, refreshing = false, history = HistoryLoad.INCOMPLETE)
        rule.onNodeWithText("Your full listening history loads on Wi-Fi.").assertIsDisplayed()
        rule.onNodeWithTag("statsLoadNow").performClick()
        assertEquals(3, loadNow)
    }

    @Test fun aYearWithAReview_offersIt_otherwiseNot() {
        var opened = 0
        var state by mutableStateOf(listened.copy(period = StatsPeriod.YEAR, reviewYear = 2026))
        rule.setContent { LauschigTheme { StatsScreen(state, onBack = {}, onOpenReview = { opened = it }) } }
        rule.onNodeWithTag("statsList").performScrollToNode(hasTestTag("statsReview"))
        rule.onNodeWithTag("statsReview").assertTextEquals("View year in review 2026").performClick()
        assertEquals(2026, opened)
        state = state.copy(reviewYear = null)
        rule.onNodeWithTag("statsReview").assertDoesNotExist()
    }
}
