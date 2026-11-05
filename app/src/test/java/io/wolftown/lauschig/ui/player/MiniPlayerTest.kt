package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.playback.StreamLoad
import android.app.Application
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class MiniPlayerTest {
    @get:Rule val rule = createComposeRule()

    private val loaded = PlayerUiState(
        itemId = "b1", title = "The Book", chapterTitle = "Chapter 2", positionSec = 125.0, durationSec = 300.0,
        chapterStartSec = 100.0, chapterEndSec = 200.0,
    )

    @Test fun miniPlayer_toggleTarget_isAtLeast56dp() {
        var toggles = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded, { toggles++ }, {}) } }
        rule.onNodeWithContentDescription("Play").assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).performClick()
        assertEquals(1, toggles)
    }

    @Test fun miniPlayer_showsPauseWhenPlaying_andOpensOnBodyTap() {
        var opened = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isPlaying = true), {}, { opened++ }) } }
        rule.onNodeWithContentDescription("Pause").assertHeightIsAtLeast(56.dp)
        rule.onNodeWithText("The Book").performClick()
        assertEquals(1, opened)
    }

    @Test fun miniPlayer_progressLine_runsAlongTheTopEdge() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(chapterIndex = 1), {}, {}) } }
        val bar = rule.onNodeWithTag("miniPlayer").fetchSemanticsNode().boundsInRoot
        val line = rule.onNodeWithTag("miniProgress", useUnmergedTree = true)
        line.assertExists().assertHeightIsEqualTo(2.dp)
        // Chapter progress: 25 of 100 s.
        line.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)))
        val bounds = line.fetchSemanticsNode().boundsInRoot
        assertEquals(bar.top, bounds.top, 0.5f)
        assertEquals(bar.width, bounds.width, 0.5f)
    }

    @Test fun miniPlayer_hiddenWhenNothingLoaded() {
        rule.setContent { LauschigTheme { MiniPlayer(PlayerUiState(), {}, {}) } }
        rule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test fun miniPlayer_loading_showsSpinner_andToggleStillWorks() {
        var toggles = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isLoading = true), { toggles++ }, {}) } }
        rule.onNodeWithContentDescription("Loading…").assertExists()
        rule.onNodeWithContentDescription("Play").assertDoesNotExist()
        rule.onNodeWithContentDescription("Loading…").performClick()
        assertEquals(1, toggles)
    }

    @Test fun miniPlayer_streamLoading_showsCompactProgressInsteadOfChapter() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isLoading = true, isStreaming = true, streamLoad = StreamLoad(250_000, 1_000_000, 100_000, 8)), {}, {}) } }
        rule.onNodeWithText("25 % · 8 s left").assertExists()
        rule.onNodeWithText("Chapter 2").assertDoesNotExist()
    }

    @Test fun miniPlayer_streamLoadingWithoutExpectation_showsRate() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isLoading = true, isStreaming = true, streamLoad = StreamLoad(250_000, null, 100_000, null)), {}, {}) } }
        rule.onNodeWithText("Loading ·", substring = true).assertExists()
    }

    @Test fun miniPlayer_throttledDownloadWhileLoading_showsHint() {
        val load = StreamLoad(250_000, null, 100_000, null)
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isLoading = true, isStreaming = true, streamLoad = load, downloadThrottled = true), {}, {}) } }
        rule.onNodeWithText("Download slow – stream has priority").assertExists()
    }

    @Test fun miniPlayer_throttledDownloadWhilePlaying_keepsChapterTitle() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isPlaying = true, isStreaming = true, downloadThrottled = true), {}, {}) } }
        rule.onNodeWithText("Chapter 2").assertExists()
        rule.onNodeWithText("Download slow – stream has priority").assertDoesNotExist()
    }

    @Test fun miniPlayer_deviceTitle_saysOnThisDevice_notDownloaded() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(itemId = "local:f1"), {}, {}) } }
        rule.onNodeWithContentDescription("On this device").assertExists()
        rule.onNodeWithContentDescription("Downloaded").assertDoesNotExist()
    }

    @Test fun miniPlayer_downloadedServerTitle_staysDownloaded() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded, {}, {}) } }
        rule.onNodeWithContentDescription("Downloaded").assertExists()
    }

    @Test fun miniPlayer_swipeUp_opensThePlayer() {
        var opened = 0
        var toggles = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded, { toggles++ }, { opened++ }) } }
        rule.onNodeWithTag("miniPlayer").performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(1, opened)
        assertEquals(0, toggles)
    }

    @Test fun miniPlayer_swipeDownOrATinyNudge_opensNothing() {
        var opened = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded, {}, { opened++ }) } }
        rule.onNodeWithTag("miniPlayer").performTouchInput { swipeDown() }
        // A slow nudge of 30 dp up: past the touch slop (16 dp here), so no tap either, but far from a swipe.
        rule.onNodeWithTag("miniPlayer").performTouchInput {
            down(Offset(centerX, centerY))
            repeat(30) { moveBy(Offset(0f, -1.dp.toPx()), delayMillis = 50) }
            up()
        }
        rule.waitForIdle()
        assertEquals(0, opened)
    }

    @Test fun miniPlayer_swipeUpFromThePlayButton_opensToo_butATapStillToggles() {
        var opened = 0
        var toggles = 0
        rule.setContent { LauschigTheme { MiniPlayer(loaded, { toggles++ }, { opened++ }) } }
        // A real swipe goes on past the button.
        rule.onNodeWithContentDescription("Play").performTouchInput { swipeUp(startY = bottom, endY = top - 60.dp.toPx(), durationMillis = 150) }
        rule.onNodeWithContentDescription("Play").performClick()
        rule.waitForIdle()
        assertEquals(1, opened)
        assertEquals(1, toggles)
    }

    @Test fun opensPlayer_upFarEnoughOrFastEnough_neverDown() {
        assertEquals(true, opensPlayer(dragPx = -50f, velocityPx = 0f, swipePx = 40f, flickPx = 600f))
        assertEquals(true, opensPlayer(dragPx = -10f, velocityPx = -900f, swipePx = 40f, flickPx = 600f))
        assertEquals(false, opensPlayer(dragPx = -10f, velocityPx = -100f, swipePx = 40f, flickPx = 600f))
        assertEquals(false, opensPlayer(dragPx = 80f, velocityPx = -900f, swipePx = 40f, flickPx = 600f)) // down, a bounce back
        assertEquals(false, opensPlayer(dragPx = 0f, velocityPx = 0f, swipePx = 40f, flickPx = 600f))
    }
}
