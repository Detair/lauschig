package io.wolftown.lauschig.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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

    @Test fun lightTheme_disablesPureBlack_belowAndroid12_noDynamicColors() {
        rule.setContent { LauschigTheme { Column { AppearanceSection(Appearance(ThemeMode.LIGHT), AppearanceActions(), dynamicColorAvailable = false) } } }
        rule.onNodeWithTag("pureBlack").assertIsNotEnabled()
        rule.onNodeWithText("Only in the dark theme").assertExists()
        rule.onNodeWithTag("dynamicColor").assertDoesNotExist()
    }
}
