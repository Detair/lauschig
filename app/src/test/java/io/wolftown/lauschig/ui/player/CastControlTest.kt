package io.wolftown.lauschig.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.wolftown.lauschig.cast.CastActivity
import io.wolftown.lauschig.cast.CastUi
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h1200dp")
class CastControlTest {
    @get:Rule val rule = createComposeRule()

    private fun button(ui: CastUi, deviceTitle: Boolean) = rule.setContent { LauschigTheme { CastButton(ui, deviceTitle) } }

    @Test fun noDevice_noButton() {
        button(CastUi(routeAvailable = false), deviceTitle = false)
        rule.onNodeWithTag("castButton").assertDoesNotExist()
        rule.onNodeWithTag("castButtonDeviceTitle").assertDoesNotExist()
    }

    @Test fun noDevice_noExplanationEither() {
        button(CastUi(routeAvailable = false), deviceTitle = true)
        rule.onNodeWithTag("castButtonDeviceTitle").assertDoesNotExist()
    }

    @Test fun aCarConnected_noButton() {
        button(CastUi(routeAvailable = true, carConnected = true), deviceTitle = true)
        rule.onNodeWithTag("castButtonDeviceTitle").assertDoesNotExist()
    }

    @Test fun aDeviceTitle_theButtonExplains_insteadOfFailingSilently() {
        button(CastUi(routeAvailable = true), deviceTitle = true)
        rule.onNodeWithContentDescription("Play on a Cast device: not possible for this title").performClick()
        rule.onNodeWithText("Only on this device").assertExists()
        rule.onNodeWithText("This title is in a folder on this device", substring = true).assertExists()
        rule.onNodeWithText("Got it").performClick()
        rule.onNodeWithText("Only on this device").assertDoesNotExist()
    }

    @Test fun thePlayerSaysWhereItPlays() {
        rule.setContent {
            LauschigTheme { PlayerScreen(PlayerScreenState(itemId = "li", title = "T", castDevice = "Living Room TV", castActivity = CastActivity.PLAYING), onClose = {}) }
        }
        rule.onNodeWithText("Playing on Living Room TV").assertExists()
        rule.onNodeWithTag("sourceStreaming").assertDoesNotExist()
        rule.onNodeWithTag("sourceLocal").assertDoesNotExist()
    }

    @Test fun onThisDevice_noCastLine_theSourceIsBack() {
        rule.setContent {
            LauschigTheme { PlayerScreen(PlayerScreenState(itemId = "li", title = "T", isStreaming = true), onClose = {}) }
        }
        rule.onNodeWithTag("castOutput").assertDoesNotExist()
        rule.onNodeWithTag("sourceStreaming").assertExists()
    }

    @Test fun aDeviceWithoutAName_stillReads() {
        rule.setContent { LauschigTheme { CastOutputLine("", CastActivity.PLAYING) } }
        rule.onNodeWithText("Playing on the Cast device").assertExists()
    }

    /** It does not say "playing" where nothing plays there. */
    @Test fun theLine_saysWhatIsTrue() {
        var activity by androidx.compose.runtime.mutableStateOf(CastActivity.CONNECTED)
        rule.setContent { LauschigTheme { CastOutputLine("Living Room TV", activity) } }
        rule.onNodeWithText("Connected to Living Room TV").assertExists()
        activity = CastActivity.SUSPENDED
        rule.onNodeWithText("Connection to the Cast device interrupted", substring = true).assertExists()
        rule.onNodeWithText("Playing on Living Room TV").assertDoesNotExist()
        activity = CastActivity.NONE
        rule.onNodeWithTag("castOutput").assertDoesNotExist()
        activity = CastActivity.PLAYING
        rule.onNodeWithText("Playing on Living Room TV").assertExists()
    }

    @Test fun whyACastEnded_isReadable() {
        rule.setContent {
            LauschigTheme {
                PlayerScreen(PlayerScreenState(itemId = "li", title = "T", error = io.wolftown.lauschig.R.string.cast_unreachable), onClose = {})
            }
        }
        rule.onNodeWithTag("playerError").assertExists()
        rule.onNodeWithText("The Cast device cannot reach your server", substring = true).assertExists()
    }

    @Test fun theSpeedSheet_namesTheLimit_whileCasting() {
        rule.setContent { LauschigTheme { PlayerScreen(PlayerScreenState(itemId = "li", title = "T", castDevice = "TV"), onClose = {}) } }
        rule.onNodeWithTag("speedButton").performClick()
        rule.onNodeWithTag("castSpeedLimit").assertExists()
    }

    /** Boost, compressor and skip silence do not pretend. */
    @Test fun theSoundSheet_whileCasting_isNotAvailable_andTheSettingsStay() {
        val state = ComfortUiState(skipSilence = true, compressor = true, titleBoostDb = 6, casting = true)
        var changes = 0
        rule.setContent {
            LauschigTheme {
                Row { ComfortButtons(state, {}, {}, { changes++ }, { changes++ }, onCompressor = { changes++ }) }
            }
        }
        rule.onNodeWithContentDescription("Sound: not available while casting").assertExists().performClick()
        rule.onNodeWithTag("soundUnavailable").assertExists()
        rule.onNodeWithTag("compressor").assertIsNotEnabled()
        rule.onNodeWithTag("skipSilence").assertIsNotEnabled()
        rule.onNodeWithTag("boost:6").assertIsNotEnabled().assertIsNotSelected()
        rule.onNodeWithTag("skipSilence").performClick()
        org.junit.Assert.assertEquals(0, changes)
    }

}
