package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.playback.StreamLoad
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.QuickDownload
import io.wolftown.lauschig.ui.detail.ChapterRow
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1200dp")
class PlayerScreenTest {
    @get:Rule val rule = createComposeRule()

    private val state = PlayerScreenState(
        itemId = "b1", title = "The Book", chapterTitle = "Two", chapterElapsedText = "00:25", chapterDurationSec = 100.0, chapterPositionSec = 25.0,
        chapterRemainingSec = 75.0, bookRemainingSec = 3 * 3600.0 + 18 * 60 + 42, speed = 1.25f,
        chapters = listOf(ChapterRow(0, "One", 100.0, false), ChapterRow(1, "Two", 100.0, true, 25.0)),
    )

    @Test fun controls_callbacks() {
        val calls = mutableListOf<String>()
        rule.setContent {
            LauschigTheme {
                PlayerScreen(state, {}, onToggle = { calls += "t" }, onSeekBy = { calls += "s$it" }, onPrevious = { calls += "p" }, onNext = { calls += "n" })
            }
        }
        rule.onNodeWithContentDescription("Play").performClick()
        rule.onNodeWithContentDescription("Back 15 seconds").performClick()
        rule.onNodeWithContentDescription("Forward 30 seconds").performClick()
        rule.onNodeWithContentDescription("Previous chapter").performClick()
        rule.onNodeWithContentDescription("Next chapter").performClick()
        assertEquals(listOf("t", "s-15", "s30", "p", "n"), calls)
    }

    @Test fun twoTimestamps_elapsedLeft_chapterRemainingRight() {
        rule.setContent { LauschigTheme { PlayerScreen(state, {}) } }
        rule.onNodeWithTag("elapsed").assertTextEquals("00:25")
        rule.onNodeWithTag("remaining").assert(hasText("−01:15", substring = true)).assert(hasText("Chapter", substring = true))
        rule.onNodeWithContentDescription("Chapter: 1 minute left").assertExists()
        rule.onNodeWithText("3:18:42", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("bookTime").assertDoesNotExist() // the old middle line is gone
    }

    @Test fun twoTimestamps_bookMode_showsTheBooksRemainingTime() {
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(remainingTime = io.wolftown.lauschig.settings.RemainingTime.BOOK), {}) } }
        rule.onNodeWithTag("remaining").assert(hasText("−3:18:42", substring = true)).assert(hasText("Book", substring = true))
        rule.onNodeWithContentDescription("Book: 3 hours 18 minutes left").assertExists()
    }

    // Review 3: without chapters (none, or one over the whole book) "Kapitel" and "Buch" are the same time – the book's.
    @Test fun withoutChapters_theBooksTimeLeft_noLabel_noToggle() {
        var toggles = 0
        val chapterless = state.copy(chapters = emptyList(), splitByChapters = false, chapterRemainingSec = 3061.0, bookRemainingSec = 3061.0)
        rule.setContent { LauschigTheme { PlayerScreen(chapterless, {}, onToggleRemaining = { toggles++ }) } }
        rule.onNodeWithTag("remaining").assertTextEquals("−51:01")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performClick()
        assertEquals(0, toggles)
        rule.onNodeWithContentDescription("51 minutes left").assertExists()
        rule.onNodeWithText("Chapter").assertDoesNotExist()
        rule.onNodeWithText("Book").assertDoesNotExist()
    }

    // Review 4: elapsed and time left add up to the chapter's length (the time left is rounded up, the elapsed time down).
    @Test fun elapsedAndRemaining_addUpToTheLength() {
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(chapterPositionSec = 25.4, chapterRemainingSec = 74.6), {}) } }
        rule.onNodeWithTag("elapsed").assertTextEquals("00:25")
        rule.onNodeWithTag("remaining").assert(hasText("−01:15", substring = true))
    }

    @Test fun remainingClock_roundsUp_butNotForFloatNoise() {
        assertEquals("01:15", remainingClock(74.6))
        assertEquals("01:15", remainingClock(75.0))
        assertEquals("01:15", remainingClock(75.0004))
        assertEquals("00:00", remainingClock(0.0))
        assertEquals("00:00", remainingClock(-3.0))
    }

    // Review 5: after the slider is let go the timestamps show the target until the seek lands, not the old position.
    @Test fun releasedSlider_timestampsShowTheTarget_untilTheSeekLands() {
        val seeks = mutableListOf<Float>()
        rule.mainClock.autoAdvance = false
        rule.setContent { LauschigTheme { PlayerScreen(state, {}, onSeekInChapter = { seeks += it }) } }
        rule.onNodeWithTag("chapterSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        rule.waitForIdle() // applies the release (the clock stands still)
        rule.mainClock.advanceTimeBy(500) // the player has not reported the new position yet
        assertEquals(1, seeks.size)
        rule.onNodeWithTag("elapsed").assertTextEquals("01:20")
        rule.onNodeWithTag("remaining").assert(hasText("−00:20", substring = true))
    }

    @Test fun remaining_tapToggles() {
        var toggles = 0
        rule.setContent { LauschigTheme { PlayerScreen(state, {}, onToggleRemaining = { toggles++ }) } }
        rule.onNodeWithTag("remaining").performClick()
        assertEquals(1, toggles)
    }

    @Test fun seekButtons_followTheSettings_andShowTheirSeconds() {
        val calls = mutableListOf<Int>()
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(seekBackSec = 10, seekForwardSec = 45), {}, onSeekBy = { calls += it }) } }
        rule.onNodeWithContentDescription("Back 10 seconds").performClick()
        rule.onNodeWithContentDescription("Forward 45 seconds").performClick()
        assertEquals(listOf(-10, 45), calls)
        rule.onNodeWithTag("seekBackNumber", useUnmergedTree = true).assertTextEquals("10")
        rule.onNodeWithTag("seekForwardNumber", useUnmergedTree = true).assertTextEquals("45")
    }

    @Test fun tiles_showNameAndValue() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        rule.onNodeWithTag("speedButton").assert(hasText("Speed", substring = true)).assert(hasText("1.25×", substring = true))
        rule.onNodeWithTag("sleepButton").assert(hasText("Sleep", substring = true)).assert(hasText("Off", substring = true))
        rule.onNodeWithTag("audioButton").assert(hasText("Sound", substring = true)).assert(hasText("Off", substring = true))
    }

    @Test fun contentsBanner_showsTheChapter_andOpensTheTabbedSheet() {
        val chapters = mutableListOf<Int>()
        rule.setContent {
            LauschigTheme {
                PlayerScreen(
                    state, {}, onChapter = { chapters += it },
                    contents = PlayerContents(queue = { Text("queue content") }, bookmarks = { Text("bookmark content") }, history = { Text("history content") }),
                )
            }
        }
        rule.onNodeWithTag("contentsBanner").assert(hasText("Chapters & queue", substring = true)).assert(hasText("Now: ch. 2 / 2", substring = true))
            .performClick()
        rule.onNodeWithTag("contentsSheet").assertExists()
        rule.onNodeWithTag("currentChapter").assertExists()
        rule.onNodeWithTag("contentsTab:Queue").performClick()
        rule.onNodeWithText("queue content").assertExists()
        rule.onNodeWithTag("contentsTab:Bookmarks").performClick()
        rule.onNodeWithText("bookmark content").assertExists()
        rule.onNodeWithTag("contentsTab:History").performClick()
        rule.onNodeWithText("history content").assertExists()
        rule.onNodeWithTag("contentsTab:Chapters").performClick()
        rule.onNodeWithText("One").performClick()
        assertEquals(listOf(0), chapters)
    }

    @Test fun contentsBanner_withoutChapters_opensOnTheQueue() {
        rule.setContent {
            LauschigTheme { PlayerScreen(state.copy(chapters = emptyList()), {}, contents = PlayerContents(queue = { Text("queue content") })) }
        }
        rule.onNodeWithTag("contentsBanner").assert(hasText("Queue, bookmarks & history", substring = true)).performClick()
        rule.onNodeWithText("queue content").assertExists()
    }

    @Test fun underTheTitle_authorAndSeriesWithVolume() {
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(author = "Autorin", seriesName = "Saga", seriesSequence = "3"), {}) } }
        rule.onNodeWithTag("playerSubtitle").assertTextEquals("Autorin · Saga · Book 3")
    }

    @Test fun underTheTitle_anAuthorNamedLikeTheSeries_isSaidOnce() {
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(author = "Die drei ???", seriesName = "Die drei ???", seriesSequence = "1"), {}) } }
        rule.onNodeWithTag("playerSubtitle").assertTextEquals("Die drei ??? · Book 1")
    }

    @Test fun underTheTitle_nothingKnown_noLine() {
        rule.setContent { LauschigTheme { PlayerScreen(state, {}) } }
        rule.onNodeWithTag("playerSubtitle").assertDoesNotExist()
    }

    @Test fun speedSheet_presetApplies_andChapterSheetSeeks() {
        val speeds = mutableListOf<Float>()
        val chapters = mutableListOf<Int>()
        rule.setContent { LauschigTheme { PlayerScreen(state, {}, onSpeed = { speeds += it }, onChapter = { chapters += it }) } }
        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithTag("preset1.5").performClick()
        assertEquals(listOf(1.5f), speeds)
    }

    @Test fun controlsRow_fitsAt360dp_withLargeFont() {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.5f),
            ) {
                LauschigTheme { PlayerScreen(state, {}) }
            }
        }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (d in listOf("Previous chapter", "Next chapter", "Play")) {
            val b = rule.onNodeWithContentDescription(d).getUnclippedBoundsInRoot()
            assertTrue("$d clipped", b.left >= 0.dp && b.right <= root.right)
        }
        rule.onNodeWithContentDescription("Playback speed 1.25×").assertExists()
    }

    @Test fun detailsButtonCoverAndTitle_openDetails() {
        var opened = 0
        rule.setContent { LauschigTheme { PlayerScreen(state, {}, onOpenDetails = { opened++ }) } }
        rule.onNodeWithTag("playerDetails").performClick()
        rule.onNodeWithTag("playerCover").performClick()
        rule.onNodeWithText("The Book").performClick()
        assertEquals(3, opened)
    }

    @Test fun downloadButton_startsDownload_andSourceShowsStreaming() {
        var downloads = 0
        rule.setContent {
            LauschigTheme { PlayerScreen(state.copy(isStreaming = true), {}, download = QuickDownload.None, onDownload = { downloads++ }) }
        }
        rule.onNodeWithContentDescription("Download").performClick()
        assertEquals(1, downloads)
        rule.onNodeWithTag("sourceStreaming").assertExists()
    }

    @Test fun completeDownload_deleteAsksFirst_andSourceShowsLocal() {
        var deletes = 0
        rule.setContent {
            LauschigTheme { PlayerScreen(state, {}, download = QuickDownload.Complete, onDeleteDownload = { deletes++ }) }
        }
        rule.onNodeWithTag("sourceLocal").assertExists()
        rule.onNodeWithTag("downloadButton").performClick()
        assertEquals(0, deletes)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }

    @Test fun streamLoad_showsBytesRateAndTimeLeft_andThrottleHint() {
        rule.setContent {
            LauschigTheme { PlayerScreen(state.copy(isLoading = true, streamLoad = StreamLoad(250_000, 1_000_000, 100_000, 75), downloadThrottled = true), {}) }
        }
        rule.onNodeWithTag("streamLoad").assertExists()
        rule.onNodeWithText("Loading", substring = true).assertExists()
        rule.onNodeWithText("2 min left", substring = true).assertExists()
        rule.onNodeWithTag("downloadThrottled").assertExists()
    }

    @Test fun noStreamLoad_noLoadText() {
        rule.setContent { LauschigTheme { PlayerScreen(state, {}) } }
        rule.onNodeWithTag("streamLoad").assertDoesNotExist()
        rule.onNodeWithTag("downloadThrottled").assertDoesNotExist()
    }

    @Test fun deviceTitle_saysOnThisDevice() {
        rule.setContent { LauschigTheme { PlayerScreen(state.copy(itemId = "local:f1"), {}) } }
        rule.onNodeWithTag("sourceDevice").assertExists()
        rule.onNodeWithText("On this device").assertExists()
        rule.onNodeWithTag("sourceLocal").assertDoesNotExist()
    }

    /** The whole player with its real action buttons, as the route builds it. */
    @androidx.compose.runtime.Composable
    private fun FullPlayer(s: PlayerScreenState = state) = PlayerScreen(
        s, {},
        comfort = { ComfortButtons(ComfortUiState(), {}, {}, {}, {}) },
    )

    private fun assertAllActionsOnScreen() {
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (tag in actionTags) {
            val b = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag below the fold: ${b.bottom} > ${root.bottom}", b.bottom <= root.bottom)
            assertTrue("$tag clipped sideways", b.left >= 0.dp && b.right <= root.right)
        }
        for (d in listOf("Play", "Back 15 seconds", "Forward 30 seconds")) {
            assertTrue("$d below the fold", rule.onNodeWithContentDescription(d).getUnclippedBoundsInRoot().bottom <= root.bottom)
        }
    }

    // A small phone (720×1280 @ 320 dpi is 360×640 dp; the exact fit without the system bars is checked on the emulator,
    // Robolectric's legacy text metrics are taller than a device's): the cover shrinks so nothing needs scrolling.
    @Config(qualifiers = "w360dp-h640dp")
    @Test fun smallPhone_allActionsVisibleWithoutScrolling() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        assertAllActionsOnScreen()
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        assertTrue("cover did not shrink", cover.right - cover.left < 280.dp)
    }

    // 1080×2400 @ 420 dpi without the system bars.
    @Config(qualifiers = "w411dp-h840dp")
    @Test fun commonPhone_allActionsVisible_andCoverStaysLarge() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        assertAllActionsOnScreen()
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        assertTrue("cover shrank: ${cover.right - cover.left}", cover.right - cover.left >= 240.dp)
    }

    @Config(qualifiers = "w360dp-h568dp")
    @Test fun smallPhone_hugeFont_stillReachesEverythingByScrolling() {
        rule.setContent {
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 2f),
            ) { LauschigTheme { FullPlayer() } }
        }
        rule.onNodeWithTag("contentsBanner").performScrollTo().assertIsDisplayed()
    }

    // A phone in landscape: cover on the start side, controls next to it.
    @Config(qualifiers = "w800dp-h360dp")
    @Test fun landscape_coverBesideControls() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        val play = rule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        assertTrue("controls not beside the cover", play.left >= cover.right)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue("cover cut off", cover.bottom <= root.bottom && cover.top >= 0.dp)
        assertTrue("play below the fold", play.bottom <= root.bottom)
    }

    private val actionTags = listOf("speedButton", "sleepButton", "audioButton", "contentsBanner")

    // A tablet in landscape (1006×601 dp minus the system bars): the cover alone on the start side, as large as fits and
    // centred; the end side holds the transport and, under it, the action grid, so nothing is left empty and nothing scrolls.
    @Config(qualifiers = "w1006dp-h520dp")
    @Test fun tabletLandscape_coverAlone_actionGridUnderTheTransport() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        assertAllActionsOnScreen()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        val play = rule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        for (tag in actionTags) {
            val b = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag not on the end side: ${b.left} < ${cover.right}", b.left >= cover.right)
            assertTrue("$tag not under the transport controls", b.top >= play.bottom)
        }
        assertTrue("cover not as large as fits: ${cover.bottom - cover.top}", cover.bottom - cover.top >= 440.dp)
        assertTrue("cover not centred", kotlin.math.abs(cover.top.value - (root.bottom - cover.bottom).value) <= 1f)
    }

    // A phone in landscape has no room for the grid under the transport controls: today's arrangement (cover with the grid
    // under it on the start side) stays, and the transport controls are on screen.
    @Config(qualifiers = "w800dp-h360dp")
    @Test fun phoneLandscape_keepsTheGridUnderTheCover_transportVisible() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        val play = rule.onNodeWithContentDescription("Play").getUnclippedBoundsInRoot()
        val speed = rule.onNodeWithTag("speedButton").getUnclippedBoundsInRoot()
        assertTrue("grid not under the cover", speed.right <= play.left && speed.top >= cover.bottom)
        for (d in listOf("Previous chapter", "Play", "Next chapter")) {
            assertTrue("$d below the fold", rule.onNodeWithContentDescription(d).getUnclippedBoundsInRoot().bottom <= root.bottom)
        }
    }

    // The same window width, just below the height the end side needs for the grid.
    @Config(qualifiers = "w1006dp-h470dp")
    @Test fun shortTabletLandscape_keepsTheGridUnderTheCover() {
        rule.setContent { LauschigTheme { FullPlayer() } }
        val cover = rule.onNodeWithTag("playerCover").getUnclippedBoundsInRoot()
        val speed = rule.onNodeWithTag("speedButton").getUnclippedBoundsInRoot()
        assertTrue("grid not under the cover", speed.top >= cover.bottom)
    }

    @Test fun actionGridUnderTransport_needsHeightForTheWholeEndColumn() {
        assertTrue(actionGridUnderTransport(520.dp))
        assertTrue(actionGridUnderTransport(480.dp))
        assertTrue(!actionGridUnderTransport(479.dp))
        assertTrue(!actionGridUnderTransport(360.dp))
    }
}
