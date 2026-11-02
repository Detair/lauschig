package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.playback.StreamLoad
import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun miniPlayer_throttledDownload_showsHint() {
        rule.setContent { LauschigTheme { MiniPlayer(loaded.copy(isStreaming = true, downloadThrottled = true), {}, {}) } }
        rule.onNodeWithText("Download slowed – stream has priority").assertExists()
    }
}
