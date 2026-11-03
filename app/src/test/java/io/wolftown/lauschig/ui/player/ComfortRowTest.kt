package io.wolftown.lauschig.ui.player

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1200dp")
class ComfortRowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun runningTimer_showsCountdown_andShakeHint() {
        val state = ComfortUiState(sleep = SleepTimerUi(SleepMode.Minutes(30), remainingMs = 754_200, shakeToExtend = false))
        rule.setContent { LauschigTheme { ComfortRowForTest(state, {}, {}, {}, {}) } }
        rule.onNodeWithText("12:35").assertExists()
        rule.onNodeWithContentDescription("Sleep timer: 12:35 left").assertExists()
        rule.onNodeWithTag("shakeHint").assertDoesNotExist()
    }

    @Test fun lastMinute_showsShakeHint() {
        val state = ComfortUiState(sleep = SleepTimerUi(SleepMode.EndOfChapter, remainingMs = 30_000, shakeToExtend = true))
        rule.setContent { LauschigTheme { ComfortRowForTest(state, {}, {}, {}, {}) } }
        rule.onNodeWithContentDescription("Sleep timer: end of chapter, 00:30 left").assertExists()
        rule.onNodeWithTag("shakeHint").assertExists()
    }

    @Test fun sleepSheet_startsAndCancels() {
        val modes = mutableListOf<SleepMode>()
        var cancels = 0
        rule.setContent {
            LauschigTheme {
                ComfortRowForTest(ComfortUiState(sleep = SleepTimerUi(SleepMode.Minutes(15), 60_000_0, false)), { modes += it }, { cancels++ }, {}, {})
            }
        }
        rule.onNodeWithTag("sleepButton").performClick()
        rule.onNodeWithTag("sleep:chapter").performClick()
        assertEquals(listOf<SleepMode>(SleepMode.EndOfChapter), modes)
        rule.onNodeWithTag("sleepButton").performClick()
        rule.onNodeWithTag("sleepCancel").performClick()
        assertEquals(1, cancels)
    }

    @Test fun audioSheet_skipSilenceAndBoost() {
        val skips = mutableListOf<Boolean>()
        val boosts = mutableListOf<Int?>()
        rule.setContent { LauschigTheme { ComfortRowForTest(ComfortUiState(skipSilence = false, titleBoostDb = 6, defaultBoostDb = 3), {}, {}, { skips += it }, { boosts += it }) } }
        rule.onNodeWithText("Timer").assertExists() // no timer: the button shows its short name …
        rule.onNodeWithContentDescription("Sleep timer").assertExists() // … and is described with the full one
        rule.onNodeWithTag("audioButton").performClick()
        rule.onNodeWithTag("skipSilence").performClick()
        rule.onNodeWithText("Default (+3 dB)").assertExists()
        rule.onNodeWithTag("boost:9").performClick()
        rule.onNodeWithTag("boost:default").performClick()
        assertEquals(listOf(true), skips)
        assertEquals(listOf<Int?>(9, null), boosts)
    }

    @androidx.compose.runtime.Composable
    private fun ComfortRowForTest(
        state: ComfortUiState, onStart: (SleepMode) -> Unit, onCancel: () -> Unit, onSkip: (Boolean) -> Unit, onBoost: (Int?) -> Unit,
    ) = androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.Row { ComfortButtons(state, onStart, onCancel, onSkip, onBoost) }
        ShakeHint(state)
    }
}
