package io.wolftown.lauschig.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class AppearanceSectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun choices_callBack() {
        val calls = mutableListOf<Any>()
        val actions = AppearanceActions({ calls += it }, { calls += "black:$it" }, { calls += "dyn:$it" }, { calls += it })
        rule.setContent { LauschigTheme { Column { AppearanceSection(Appearance(), actions, dynamicColorAvailable = true) } } }
        rule.onNodeWithTag("themeMode").performClick()
        rule.onNodeWithTag("themeMode:DARK").performClick()
        rule.onNodeWithTag("pureBlack").performClick()
        rule.onNodeWithTag("dynamicColor").performClick()
        rule.onNodeWithTag("fontScale:100").assertIsSelected()
        rule.onNodeWithTag("fontScale:130").performClick()
        assertEquals(listOf(ThemeMode.DARK, "black:true", "dyn:false", 130), calls)
        rule.onNodeWithTag("lookPreview").assertExists()
    }

    @Test fun darkTone_choice_callsBack_andShowsTheSelection() {
        val tones = mutableListOf<DarkThemeTone>()
        rule.setContent {
            LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.DARK, dynamicColor = false), AppearanceActions(onDarkTone = { tones += it }), dynamicColorAvailable = true) } }
        }
        rule.onNodeWithTag("darkTone:NIGHT_BLUE").assertIsSelected()
        rule.onNodeWithText("Night blue").assertExists()
        rule.onNodeWithTag("darkTone:WARM_AMBER").performClick()
        assertEquals(listOf(DarkThemeTone.WARM_AMBER), tones)
    }

    @Test fun darkTone_withDynamicColors_theFirstChoiceSaysSo() {
        rule.setContent {
            LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.DARK, dynamicColor = true, darkTone = DarkThemeTone.WARM_AMBER), AppearanceActions(), dynamicColorAvailable = true) } }
        }
        rule.onNodeWithTag("darkTone:WARM_AMBER").assertIsSelected()
        // The regular dark tone is the wallpaper's then; the fireside also replaces it.
        rule.onNodeWithTag("darkTone:NIGHT_BLUE").assertTextEquals("Dynamic")
        rule.onNodeWithText("Fireside: warm mocha and amber – also instead of the wallpaper colors").assertExists()
    }

    @Test fun lightTheme_disablesTheDarkTone() {
        rule.setContent { LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.LIGHT), AppearanceActions(), dynamicColorAvailable = false) } } }
        rule.onNodeWithTag("darkTone:WARM_AMBER").assertIsNotEnabled()
        rule.onNodeWithTag("darkTone:NIGHT_BLUE").assertIsNotEnabled()
    }

    @Test @Config(sdk = [35], application = android.app.Application::class, qualifiers = "de-w400dp-h1200dp")
    fun darkTone_german() {
        rule.setContent { LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.DARK, dynamicColor = false), AppearanceActions(), dynamicColorAvailable = false) } } }
        rule.onNodeWithText("Dunkler Farbton").assertExists()
        rule.onNodeWithTag("darkTone:NIGHT_BLUE").assertTextEquals("Nachtblau")
        rule.onNodeWithTag("darkTone:WARM_AMBER").assertTextEquals("Kaminfeuer")
    }

    @Test fun lightTheme_disablesPureBlack_belowAndroid12_noDynamicColors() {
        rule.setContent { LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.LIGHT), AppearanceActions(), dynamicColorAvailable = false) } } }
        rule.onNodeWithTag("pureBlack").assertIsNotEnabled()
        // Pure black and the dark tone both say why they are off.
        rule.onAllNodesWithText("Only in the dark theme").assertCountEquals(2)
        rule.onNodeWithTag("dynamicColor").assertDoesNotExist()
    }
}
