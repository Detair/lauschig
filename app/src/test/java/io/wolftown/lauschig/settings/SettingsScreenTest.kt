package io.wolftown.lauschig.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assert
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun show(prefs: PlaybackPrefs, actions: PlaybackPrefsActions) = rule.setContent {
        LauschigTheme {
            SettingsScreen(
                SettingsUiState(playback = prefs), onBack = {}, onOpenCleanup = {}, onNextVolume = {}, onWifiOnly = {}, onCleanupReminder = {},
                playbackActions = actions,
            )
        }
    }

    @Test fun seekRows_showTheCurrentSteps_andSetNewOnes() {
        val back = mutableListOf<Int>()
        val forward = mutableListOf<Int>()
        show(PlaybackPrefs(seekBackSec = 10, seekForwardSec = 45), PlaybackPrefsActions(onSeekBack = { back += it }, onSeekForward = { forward += it }))
        rule.onNodeWithTag("seekBack").performScrollTo().assert(hasText("10 s", substring = true)).performClick()
        rule.onNodeWithTag("seekBack:5").performClick()
        rule.onNodeWithTag("seekForward").performScrollTo().assert(hasText("45 s", substring = true)).performClick()
        rule.onNodeWithTag("seekForward:60").performClick()
        assertEquals(listOf(5), back)
        assertEquals(listOf(60), forward)
    }

    @Test fun seekRows_offerExactlyTheChoices() {
        show(PlaybackPrefs(), PlaybackPrefsActions())
        rule.onNodeWithTag("seekBack").performScrollTo().assert(hasText("15 s", substring = true)).performClick()
        PlaybackSettings.SEEK_BACK_CHOICES.forEach { rule.onNodeWithTag("seekBack:$it").assertExists() }
        rule.onNodeWithTag("seekBack:45").assertDoesNotExist()
    }
}
