package io.wolftown.lauschig.ui.achievements

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.AchievementState
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The achievements grid: locked, unlocked, tiers with their progress, and what TalkBack says for each card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h2000dp") // every card on screen at once
class AchievementsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun d(text: String) = LocalDate.parse(text)

    /** An hour as the locale writes a time ("4:00 AM"). */
    private fun t(hour: Int) = LocalTime.of(hour, 0).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US))

    private val hours = AchievementState(Achievement.HOURS, listOf(d("2026-01-02"), d("2026-03-12")), 37.5 * 3600)
    private val series = AchievementState(Achievement.SERIES, listOf(d("2025-01-01"), d("2025-06-01"), d("2026-02-01")), 11.0)
    private val books = AchievementState(Achievement.BOOKS, listOf(null), 3.0)
    private val owl = AchievementState(Achievement.NIGHT_OWL, emptyList(), null)
    private val streak = AchievementState(Achievement.STREAK, emptyList(), 2.0)
    private val firstHour = AchievementState(Achievement.HOURS, emptyList(), 1200.0)
    private val marathon = AchievementState(Achievement.MARATHON, emptyList(), 7800.0)

    private val state = AchievementsUiState(loading = false, cards = listOf(hours, series, books, streak, owl, marathon), unlockedTiers = 6)

    private fun show(s: AchievementsUiState = state, onBack: () -> Unit = {}) {
        rule.setContent { LauschigTheme { AchievementsScreen(s, onBack) } }
    }

    @Test fun aTierCard_namesItsTier_progressToTheNext_andSinceWhen() {
        show()
        // TalkBack: one stop, its lines in order; the progress said with "of", the bar not read twice.
        rule.onNodeWithTag("achievement:hours").assertTextEquals(
            "10 hours listened", "Hours you have listened in total", "Level 2 of 7", "37 of 50 h", "Since March 12, 2026",
        )
        rule.onNodeWithTag("achievement:hours").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        rule.onNodeWithTag("progress:hours", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("badge:hours:unlocked", useUnmergedTree = true).assertExists()
    }

    @Test fun allTiersReached_noBar_reachedWithoutADay_saysReached() {
        show()
        rule.onNodeWithTag("achievement:series").assertTextEquals(
            "10 series complete", "Series with every volume finished", "Level 3 of 3", "Since February 1, 2026",
        )
        rule.onNodeWithTag("progress:series", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("achievement:books").assertTextEquals(
            "1 book finished", "Books you have heard to the end", "Level 1 of 6", "3 of 5 books", "Reached",
        )
    }

    @Test fun lockedCards_nameTheirGoal_showTheirProgress_andSaySo() {
        show(state.copy(cards = listOf(streak, owl, firstHour, marathon)))
        rule.onNodeWithTag("achievement:streak").assertTextEquals("3 days in a row", "Days in a row with at least 1 minute", "2 of 3 days", "Not reached yet")
        rule.onNodeWithTag("achievement:night_owl").assertTextEquals("Night owl", "10 minutes, started between ${t(0)} and ${t(4)}", "Not reached yet")
        rule.onNodeWithTag("achievement:hours").assertTextEquals("1 hour listened", "Hours you have listened in total", "20 min of 1 h", "Not reached yet")
        rule.onNodeWithTag("achievement:marathon").assertTextEquals("Marathon", "4 hours on one day", "2 h 10 min of 4 h", "Not reached yet")
        rule.onNodeWithTag("badge:night_owl:locked", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("progress:night_owl", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theProgressLine_showsASlash_whereTalkBackSaysOf() {
        val shown = mutableListOf<String?>()
        rule.setContent {
            LauschigTheme {
                for (s in listOf(hours, firstHour, marathon, streak, books, owl, series)) shown += achievementProgress(s)
                shown += achievementProgress(AchievementState(Achievement.COMMUTER, emptyList(), 4.0))
                shown += achievementProgress(AchievementState(Achievement.GENRES, emptyList(), 1.0))
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("37 / 50 h", "20 min / 1 h", "2 h 10 min / 4 h", "2 / 3 days", "3 / 5 books", null, null, "4 / 20 sessions", "1 / 5 genres"), shown.take(9))
    }

    @Test fun descriptions_takeTheirNumbersFromTheRules() {
        val texts = mutableListOf<String>()
        rule.setContent { LauschigTheme { Achievement.entries.forEach { texts += achievementDescription(it) } } }
        rule.waitForIdle()
        assertEquals(
            listOf(
                "Hours you have listened in total", "Days in a row with at least 1 minute", "Books you have heard to the end",
                "Series with every volume finished", "4 hours on one day", "10 minutes, started between ${t(0)} and ${t(4)}",
                "10 minutes, started between ${t(4)} and ${t(6)}", "5 hours over one Saturday and Sunday",
                "A book finished within 72 hours of starting it",
                "20 sessions on weekdays, 10 minutes each, started ${t(6)}–${t(9)} or ${t(16)}–${t(19)}",
                "Titles of 5 genres, each heard for 30 minutes",
            ),
            texts.take(Achievement.entries.size),
        )
    }

    @Test fun header_countsTheReachedTiers_backGoesBack() {
        var back = 0
        show(onBack = { back++ })
        rule.onNodeWithText("Reached: 6 of 28").assertExists()
        rule.onNodeWithTag("achievementsBack").performClick()
        assertEquals(1, back)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring
    fun theGrid_fillsColumnsOf160dp_andEveryCardOfARowIsAsTallAsTheTallest() {
        // 412 dp (a phone) less the gutters: two columns of at least 160 dp.
        show()
        val a = rule.onNodeWithTag("achievement:hours").getBoundsInRoot()
        val b = rule.onNodeWithTag("achievement:series").getBoundsInRoot()
        assertEquals(a.top, b.top)
        assertEquals(a.bottom, b.bottom)
        rule.onNodeWithTag("achievementsGrid").performScrollToNode(hasTestTag("achievement:marathon"))
        rule.onAllNodesWithTag("achievementRow").assertCountEquals(3)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h900dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun largeText_nothingCutOff() {
        show()
        val a = rule.onNodeWithTag("achievement:hours").getBoundsInRoot()
        // Each card as tall and as wide as its text needs.
        for (text in listOf("Hours you have listened in total", "Since March 12, 2026", "37 of 50 h")) {
            val bounds = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
            assertTrue("$text below the card: $bounds vs $a", bounds.bottom <= a.bottom && bounds.right <= a.right)
        }
    }

    @Test fun whileLoading_noCards() {
        show(AchievementsUiState())
        rule.onNodeWithTag("loadingState").assertExists()
        rule.onNodeWithTag("achievement:hours").assertDoesNotExist()
    }
}
