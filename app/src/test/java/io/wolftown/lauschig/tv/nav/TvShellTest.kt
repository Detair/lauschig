package io.wolftown.lauschig.tv.nav

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.common.TAG_MESSAGE
import io.wolftown.lauschig.tv.common.TvMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvShellTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val chosen = mutableListOf<TvSection>()

    private fun show(current: TvSection = TvSection.Home) = rule.setContent {
        TvTheme {
            TvShell(current, onSection = { chosen += it }) {
                Column {
                    Button(onClick = {}, modifier = Modifier.testTag("first")) { Text("First") }
                    Button(onClick = {}, modifier = Modifier.testTag("second")) { Text("Second") }
                }
            }
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun left_opensTheRail_onTheCurrentSection_withItsNames() {
        show(TvSection.Library)
        rule.onNodeWithTag("second").requestFocus()
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_RAIL + TvSection.Library.name).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.nav_search)).assertIsDisplayed()
    }

    @Test fun okOnLibrary_choosesIt() {
        show()
        rule.onNodeWithTag("first").requestFocus()
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_RAIL + TvSection.Home.name).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(TAG_RAIL + TvSection.Library.name).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(TvSection.Library), chosen)
    }

    @Test fun right_goesBackToWhereTheFocusWas() {
        show()
        rule.onNodeWithTag("second").requestFocus()
        press(Key.DirectionLeft)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        rule.onNodeWithTag("second").assertIsFocused()
    }

    @Test fun okOnTheCurrentSection_goesBack_withoutChoosing() {
        show()
        rule.onNodeWithTag("second").requestFocus()
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        rule.onNodeWithTag("second").assertIsFocused()
        assertEquals(emptyList<TvSection>(), chosen)
    }

    @Test fun back_withTheRailOpen_closesIt_andStaysOnThePage() {
        show()
        rule.onNodeWithTag("second").requestFocus()
        press(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_RAIL + TvSection.Home.name).assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("second").assertIsFocused()
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun aMessageThatAppears_leavesTheFocusInTheOpenRail() {
        var loaded by mutableStateOf(true)
        rule.setContent {
            TvTheme {
                TvShell(TvSection.Home, onSection = {}) {
                    if (loaded) Button(onClick = {}, modifier = Modifier.testTag("first")) { Text("First") } else TvMessage("Loading")
                }
            }
        }
        rule.onNodeWithTag("first").requestFocus()
        press(Key.DirectionLeft)
        loaded = false
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_MESSAGE).assertExists()
        rule.onNodeWithTag(TAG_RAIL + TvSection.Home.name).assertIsFocused()
    }
}
