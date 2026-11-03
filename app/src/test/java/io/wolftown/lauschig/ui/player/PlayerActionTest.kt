package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.ui.LauschigTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp")
class PlayerActionTest {
    @get:Rule val rule = createComposeRule()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    // The second action row on a 360 dp phone at 130 %: the longest German label must stay whole, not "Warteschla…".
    @Test fun longLabel_atLargeText_shrinksInsteadOfCuttingOff() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                LauschigTheme {
                    Row(Modifier.width(328.dp)) {
                        PlayerAction(Icons.Rounded.History, "Lesezeichen", {})
                        PlayerAction(Icons.Rounded.History, "Warteschlange", {})
                        PlayerAction(Icons.Rounded.History, "Verlauf", {})
                    }
                }
            }
        }
        assertFalse("label cut off", layoutOf("Warteschlange").hasVisualOverflow)
        assertFalse("label cut off", layoutOf("Lesezeichen").hasVisualOverflow)
    }

    // The source label in the player's top bar: one line also in German at 130 %, never "Heruntergelad / en".
    @Test fun sourceLabel_staysOnOneLine() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                LauschigTheme {
                    androidx.compose.foundation.layout.Box(Modifier.width(110.dp)) { SourceIndicator(PlaySource.DOWNLOAD) }
                }
            }
        }
        val layout = layoutOf("Downloaded")
        org.junit.Assert.assertEquals(1, layout.lineCount)
        assertFalse("label cut off", layout.hasVisualOverflow)
    }
}
