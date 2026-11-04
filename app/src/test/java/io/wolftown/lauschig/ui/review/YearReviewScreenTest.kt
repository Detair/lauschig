package io.wolftown.lauschig.ui.review

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.wolftown.lauschig.stats.Achievement
import io.wolftown.lauschig.stats.AuthorTime
import io.wolftown.lauschig.stats.FinishedBook
import io.wolftown.lauschig.stats.ListenerType
import io.wolftown.lauschig.stats.ListeningTime
import io.wolftown.lauschig.stats.ReachedTier
import io.wolftown.lauschig.stats.ReviewSlide
import io.wolftown.lauschig.stats.TitleTime
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/** Every slide in words (what TalkBack reads), the number's entrance, the empty and loading pages, large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class YearReviewScreenTest {
    @get:Rule val rule = createComposeRule()

    companion object {
        private const val H = 3600.0
        private val anfang = TitleTime("li_a", "Der Anfang", "Anna", 12 * H + 300)

        /** A year with every slide. */
        val FULL = YearReview(
            2026,
            listOf(
                ReviewSlide.Opening(2026, 47 * H + 1200, 12, incomplete = true),
                ReviewSlide.Days(87, 12, LocalDate.of(2026, 3, 14)),
                ReviewSlide.Rhythm(List(12) { if (it == 10) 21 * H + 300 else H }, Month.NOVEMBER, DayOfWeek.SUNDAY, ListeningTime.COMMUTE_MORNING),
                ReviewSlide.TopTitles(listOf(anfang, TitleTime("li_b", null, null, 600.0))),
                ReviewSlide.TopPeople(AuthorTime("Anna", 12 * H + 300), "li_a", AuthorTime("Nora", 8 * H)),
                ReviewSlide.Finished(
                    8, 2,
                    FinishedBook("li_a", "Der Anfang", "Anna", LocalDate.of(2026, 1, 12), finishedAt = 1L),
                    FinishedBook("li_c", "Das Ende", "Anna", LocalDate.of(2026, 11, 28), finishedAt = 2L),
                ),
                ReviewSlide.Achieved(2026, 3, listOf(ReachedTier(Achievement.HOURS, 3, LocalDate.of(2026, 8, 1)), ReachedTier(Achievement.MARATHON, 1, LocalDate.of(2026, 8, 1)))),
                ReviewSlide.Personality(ListenerType.NIGHT_OWL, 0.38, 0),
                ReviewSlide.Closing(2026, 47 * H + 1200, 87, 8, anfang, "Anna", ListenerType.NIGHT_OWL),
            ),
        )
    }

    private var closed = 0
    private val places = HashMap<Int, StoryState>()

    /** As the view model hands it out: one place per story length. */
    private fun place(count: Int) = places.getOrPut(count) { StoryState(count) }

    private fun show(review: YearReview?, motion: StoryMotion = StoryMotion(autoAdvance = false, animations = false), loading: Boolean = false, fontScale: Float? = null) {
        rule.setContent {
            LauschigTheme {
                val content = @androidx.compose.runtime.Composable {
                    YearReviewScreen(YearReviewUiState(2026, loading = loading, review = review), onClose = { closed++ }, story = review?.let { place(it.slides.size) }, motion = motion)
                }
                if (fontScale == null) content() else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { content() }
            }
        }
    }

    /** The slides' words, one after the other (by the "next" button). */
    private fun spokenSlides(count: Int): List<String> = (0 until count).map { i ->
        if (i > 0) rule.onNodeWithTag("storyNext").performClick()
        rule.onNodeWithTag("storySlide").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
    }

    @Test fun everySlide_saysWhatItShows_inReadingOrder() {
        show(FULL)
        val spoken = spokenSlides(9)
        assertEquals("Slide 1 of 9. Your 2026 in listening. 47 hours listened, with 12 titles. Not all listening sessions are loaded yet.", spoken[0])
        assertEquals("Slide 2 of 9. Days of listening. 87 days with listening. Longest streak: 12 days in a row, up to March 14.", spoken[1])
        assertEquals(
            "Slide 3 of 9. Your rhythm. November, your strongest month – 21 h 5 min. Favourite day: Sunday. Mostly on the way, in the morning.",
            spoken[2],
        )
        assertEquals("Slide 4 of 9. Most listened. 1. Der Anfang, 12 h 5 min. 2. Unknown title, 10 min.", spoken[3])
        assertEquals("Slide 5 of 9. Your top author. Anna, 12 h 5 min. Favourite voice: Nora, 8 h 0 min.", spoken[4])
        assertEquals(
            "Slide 6 of 9. Heard to the end. 8 books finished and 2 series completed. First: Der Anfang, January 12. Last: Das Ende, November 28.",
            spoken[5],
        )
        assertEquals("Slide 7 of 9. Achievements 2026. 3 achievements reached. 50 hours listened, Marathon and 1 more.", spoken[6])
        // The night owl's window is the achievement's: from midnight to 4:00.
        assertTrue(spoken[7], spoken[7].startsWith("Slide 8 of 9. Your listening type. Night owl. 38% of your listening started between 12:00"))
        assertEquals(
            "Slide 9 of 9. 2026 at a glance. Listening time: 47 h 20 min. Days: 87 days. Finished: 8 books. Top title: Der Anfang. " +
                "Top author: Anna. Listening type: Night owl.",
            spoken[8],
        )
    }

    @Test fun theOpeningSaysTheHistoryIsIncomplete_onlyWhenItIs() {
        show(YearReview(2026, listOf(ReviewSlide.Opening(2026, 2 * H, 1, incomplete = false), ReviewSlide.Closing(2026, 2 * H, 1, 0, null, null, null))))
        rule.onAllNodesWithTag("reviewIncomplete", useUnmergedTree = true).assertCountEquals(0)
        assertEquals(
            "Slide 1 of 2. Your 2026 in listening. 2 hours listened, with 1 title.",
            spokenSlides(1).single(),
        )
        rule.onNodeWithTag("storyNext").performClick()
        // A thin year's closing names only what it has.
        rule.onNodeWithTag("storySlide").assertContentDescriptionEquals("Slide 2 of 2. 2026 at a glance. Listening time: 2 h 0 min. Days: 1 day.")
    }

    @Test fun noTitleLineWithoutTitles_andASeriesAloneLeadsTheFinishedSlide() {
        show(
            YearReview(
                2026,
                listOf(
                    ReviewSlide.Opening(2026, 2 * H, 0, incomplete = false),
                    ReviewSlide.Finished(books = 0, series = 1, first = null, last = null, seriesItemId = "v2"),
                    ReviewSlide.Closing(2026, 2 * H, 1, 0, null, null, null),
                ),
            ),
        )
        val spoken = spokenSlides(2)
        assertEquals("Slide 1 of 3. Your 2026 in listening. 2 hours listened.", spoken[0])
        assertEquals("Slide 2 of 3. Heard to the end. 1 series completed.", spoken[1])
    }

    @Test fun aRebuildWithFewerSlides_whileTheLastOneFadesOut_neverReachesBeyondThem() {
        val three = YearReview(2026, listOf(ReviewSlide.Opening(2026, 2 * H, 1, true), FULL.slides[4], ReviewSlide.Closing(2026, 2 * H, 1, 0, null, null, null)))
        val two = YearReview(2026, listOf(ReviewSlide.Opening(2026, 3 * H, 1, false), ReviewSlide.Closing(2026, 3 * H, 1, 0, null, null, null)))
        val story = StoryState(3, index = 2)
        var state by androidx.compose.runtime.mutableStateOf(YearReviewUiState(2026, loading = false, review = three))
        rule.setContent {
            LauschigTheme { YearReviewScreen(state, onClose = {}, story = story, motion = StoryMotion(autoAdvance = false, animations = true)) }
        }
        rule.waitForIdle()
        // As the view model does it: the slides and the place in one snapshot; the closing moves from 3 to 2.
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            story.relocate(2, 1, emptySet())
            state = state.copy(review = two)
        }
        rule.onNodeWithTag("storySlide").assertContentDescriptionEquals("Slide 2 of 2. 2026 at a glance. Listening time: 3 h 0 min. Days: 1 day.")
    }

    @Test fun theBigNumberCountsUpOnce_whenAnimationsAreOn() {
        rule.mainClock.autoAdvance = false
        show(FULL, StoryMotion(autoAdvance = false, animations = true))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("reviewNumber", useUnmergedTree = true).assertTextEquals("0")
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithTag("reviewNumber", useUnmergedTree = true).assertTextEquals("47")
        rule.onNodeWithTag("reviewIncomplete", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun withoutAnimations_theNumberIsThereAtOnce() {
        rule.mainClock.autoAdvance = false
        show(FULL)
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("reviewNumber", useUnmergedTree = true).assertTextEquals("47")
    }

    @Test fun aYearWithoutAnHour_saysSo_andCloses() {
        show(null)
        rule.onNodeWithText("No year in review yet for 2026").assertIsDisplayed()
        rule.onNodeWithTag("storyClose").performClick()
        assertEquals(1, closed)
        rule.onAllNodesWithTag("story").assertCountEquals(0)
    }

    @Test fun whileLoading_noSlides_butAClose() {
        show(null, loading = true)
        rule.onAllNodesWithTag("story").assertCountEquals(0)
        rule.onAllNodesWithText("No year in review yet for 2026").assertCountEquals(0)
        rule.onNodeWithTag("storyClose").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w360dp-h420dp")
    fun doubleFontSize_theBigNumbersAndNamesFit_andALongSlideScrollsInsteadOfCutting() {
        show(FULL, fontScale = 2f)
        val big = mutableListOf<String>()
        var scrolls = 0
        repeat(9) { i ->
            if (i > 0) rule.onNodeWithTag("storyNext").performClick()
            for (tag in listOf("reviewNumber", "reviewName")) {
                for (node in rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()) {
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layouts)
                    val layout = layouts.single()
                    // On its lines, within the slide's width: shrunk to fit rather than cut or ellipsized.
                    assertTrue("$tag on slide ${i + 1} overflows", !layout.hasVisualOverflow && !layout.isLineEllipsized(layout.lineCount - 1))
                    big += layout.layoutInput.text.text
                }
            }
            val range = rule.onNodeWithTag("storySlide").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            if (range.maxValue() > 0f) scrolls++
        }
        // Every slide's one big thing was measured (the opening's hours … the type), and at 200 % on a short window (a phone in landscape)
        // at least one slide is taller than the window – it scrolls.
        assertEquals(listOf("47", "87", "November", "Anna", "8", "3", "Night owl"), big)
        assertTrue(scrolls > 0)
    }
}

/** The German words of a few slides: plurals, dates, the percent and "Uhr". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de")
class YearReviewScreenGermanTest {
    @get:Rule val rule = createComposeRule()
    private val place = StoryState(9)

    @Test fun slides_inGerman() {
        rule.setContent {
            LauschigTheme { YearReviewScreen(YearReviewUiState(2026, loading = false, review = YearReviewScreenTest.FULL), onClose = {}, story = place, motion = StoryMotion(false, false)) }
        }
        fun spoken() = rule.onNodeWithTag("storySlide").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertEquals("Folie 1 von 9. Dein Hörjahr 2026. 47 Stunden gehört, mit 12 Titeln. Noch nicht alle Hörsitzungen geladen.", spoken())
        rule.onNodeWithTag("storyNext").performClick()
        assertEquals("Folie 2 von 9. Hörtage. 87 Tage mit Hörzeit. Längste Serie: 12 Tage am Stück, bis 14. März.", spoken())
        repeat(4) { rule.onNodeWithTag("storyNext").performClick() }
        assertEquals(
            "Folie 6 von 9. Zu Ende gehört. 8 Bücher beendet und 2 Serien komplett. Das erste: Der Anfang, 12. Januar. Das letzte: Das Ende, 28. November.",
            spoken(),
        )
        repeat(2) { rule.onNodeWithTag("storyNext").performClick() }
        assertEquals("Folie 8 von 9. Dein Hörtyp. Nachteule. 38 % deiner Hörzeit begann zwischen 00:00 und 04:00 Uhr.", spoken())
        rule.onNodeWithTag("storyNext").performClick()
        assertEquals(
            "Folie 9 von 9. 2026 auf einen Blick. Hörzeit: 47 Std. 20 Min. Hörtage: 87 Tage. Beendet: 8 Bücher. Meistgehörter Titel: Der Anfang. " +
                "Meistgehört von: Anna. Hörtyp: Nachteule.",
            spoken(),
        )
    }
}
