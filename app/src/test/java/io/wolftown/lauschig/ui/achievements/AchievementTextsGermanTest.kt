package io.wolftown.lauschig.ui.achievements

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.AchievementKey
import io.wolftown.lauschig.stats.AchievementState
import androidx.compose.ui.text.TextLayoutResult
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** The German texts: names with their plurals, tiers, progress, dates and the notice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w412dp-h2000dp")
class AchievementTextsGermanTest {
    @get:Rule val rule = createComposeRule()

    @Test fun namesAndProgress_inGerman_withTheirPlurals() {
        val names = mutableListOf<String?>()
        rule.setContent {
            LauschigTheme {
                names += achievementName(Achievement.HOURS, 1)
                names += achievementName(Achievement.HOURS, 2)
                names += achievementName(Achievement.STREAK, 0) // locked: the first goal
                names += achievementName(Achievement.BOOKS, 1)
                names += achievementName(Achievement.BOOKS, 2)
                names += achievementName(Achievement.SERIES, 1)
                names += achievementName(Achievement.SERIES, 3)
                names += achievementName(Achievement.EARLY_BIRD, 1)
                names += achievementProgress(AchievementState(Achievement.HOURS, listOf(null, null), 37.5 * 3600))
                names += achievementProgress(AchievementState(Achievement.WEEKEND, emptyList(), 3 * 3600.0 + 600))
                names += achievementProgress(AchievementState(Achievement.SERIES, emptyList(), 0.0))
                names += achievementProgress(AchievementState(Achievement.COMMUTER, emptyList(), 1.0), spoken = true)
                // Said "von …": the dative.
                names += achievementProgress(AchievementState(Achievement.BOOKS, listOf(null), 3.0), spoken = true)
                names += achievementProgress(AchievementState(Achievement.STREAK, emptyList(), 1.0), spoken = true)
            }
        }
        rule.waitForIdle()
        assertEquals(
            listOf(
                "1 Stunde gehört", "10 Stunden gehört", "3 Tage am Stück", "1 Buch beendet", "5 Bücher beendet", "1 Serie komplett",
                "10 Serien komplett", "Frühaufsteher", "37 / 50 Std.", "3 Std. 10 Min. / 5 Std.", "0 / 1 Serie", "1 von 20 Sitzungen",
                "3 von 5 Büchern", "1 von 3 Tagen",
            ),
            names.take(14),
        )
    }

    @Test fun descriptions_inGerman_withTheRulesNumbers() {
        val texts = mutableListOf<String>()
        rule.setContent { LauschigTheme { Achievement.entries.forEach { texts += achievementDescription(it) } } }
        rule.waitForIdle()
        assertEquals(
            listOf(
                "Stunden, die du insgesamt gehört hast", "Tage in Folge mit mindestens 1 Minute", "Bücher, die du bis zum Ende gehört hast",
                "Serien, von denen du jeden Band beendet hast", "4 Stunden an einem Tag", "10 Minuten, begonnen zwischen 00:00 und 04:00 Uhr",
                "10 Minuten, begonnen zwischen 04:00 und 06:00 Uhr", "5 Stunden an einem Samstag und Sonntag",
                "Ein Buch binnen 72 Stunden nach dem Anfang beendet",
                "20 Sitzungen werktags zu je 10 Minuten, begonnen 06:00–09:00 oder 16:00–19:00 Uhr",
                "Titel aus 5 Genres, jeder 30 Minuten gehört",
            ),
            texts.take(Achievement.entries.size),
        )
    }

    @Test fun aCard_inGerman() {
        val state = AchievementsUiState(
            loading = false, unlockedTiers = 2,
            cards = listOf(AchievementState(Achievement.STREAK, listOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 12)), 12.0)),
        )
        rule.setContent { LauschigTheme { AchievementsScreen(state, onBack = {}) } }
        rule.onNodeWithText("Erfolge").assertExists()
        rule.onNodeWithText("2 von 28 erreicht").assertExists()
        rule.onNodeWithTag("achievement:streak").assertTextEquals(
            "7 Tage am Stück", "Tage in Folge mit mindestens 1 Minute", "Stufe 2 von 5", "12 von 30 Tagen", "Seit 12. März 2026",
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w412dp-h2000dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measuring
    fun atDoubleFontSize_fewerColumns_ratherThanAWordBroken() {
        val cards = listOf(
            AchievementState(Achievement.EARLY_BIRD, listOf(LocalDate.of(2026, 11, 4)), null),
            AchievementState(Achievement.STREAK, listOf(LocalDate.of(2026, 11, 4)), 3.0),
            AchievementState(Achievement.WEEKEND, emptyList(), 600.0),
        )
        rule.setContent { LauschigTheme { AchievementsScreen(AchievementsUiState(loading = false, cards = cards, unlockedTiers = 2), onBack = {}) } }
        // Two columns of 184 dp would break „Frühaufsteher“: one column.
        val first = rule.onNodeWithTag("achievement:early_bird").getBoundsInRoot()
        val second = rule.onNodeWithTag("achievement:streak").getBoundsInRoot()
        assertEquals(first.left, second.left)
        for (word in listOf("Frühaufsteher", "Hörwochenende")) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(word, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            assertEquals("$word on one line", 1, layouts.single().lineCount)
        }
    }

    @Test fun theNotice_inGerman() {
        rule.setContent {
            LauschigTheme {
                val one = remember { SnackbarHostState() }
                val many = remember { SnackbarHostState() }
                SnackbarHost(one)
                SnackbarHost(many)
                AchievementNoticeSnackbar(AchievementNotice("s", listOf(AchievementKey("streak", 2))), one, onShown = {}, onOpen = {})
                AchievementNoticeSnackbar(AchievementNotice("s", listOf(AchievementKey("hours", 1), AchievementKey("genres", 1), AchievementKey("one_go", 1))), many, onShown = {}, onOpen = {})
            }
        }
        rule.onNodeWithText("Neuer Erfolg: 7 Tage am Stück").assertExists()
        rule.onNodeWithText("3 neue Erfolge").assertExists()
    }
}
