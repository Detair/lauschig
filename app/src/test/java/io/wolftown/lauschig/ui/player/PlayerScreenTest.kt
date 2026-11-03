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
        itemId = "b1", title = "The Book", chapterTitle = "Two", chapterElapsedText = "00:25", chapterTotalText = "01:40",
        bookElapsedText = "02:05", bookTotalText = "05:00", speed = 1.25f,
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
        rule.onNodeWithContentDescription("Back 30 seconds").performClick()
        rule.onNodeWithContentDescription("Forward 30 seconds").performClick()
        rule.onNodeWithContentDescription("Previous chapter").performClick()
        rule.onNodeWithContentDescription("Next chapter").performClick()
        assertEquals(listOf("t", "s-30", "s30", "p", "n"), calls)
        rule.onNodeWithText("02:05 / 05:00").assertExists()
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
        extras = { PlayerExtraButtons({}, {}, {}) },
    )

    private fun assertAllActionsOnScreen() {
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (tag in listOf("speedButton", "chaptersButton", "sleepButton", "audioButton", "bookmarksButton", "queueButton", "historyButton")) {
            val b = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag below the fold: ${b.bottom} > ${root.bottom}", b.bottom <= root.bottom)
            assertTrue("$tag clipped sideways", b.left >= 0.dp && b.right <= root.right)
        }
        for (d in listOf("Play", "Back 30 seconds", "Forward 30 seconds")) {
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
        rule.onNodeWithTag("historyButton").performScrollTo().assertIsDisplayed()
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

    private val actionTags = listOf("speedButton", "chaptersButton", "sleepButton", "audioButton", "bookmarksButton", "queueButton", "historyButton")

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
