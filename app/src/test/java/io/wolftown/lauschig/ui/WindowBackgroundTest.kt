package io.wolftown.lauschig.ui

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WindowBackgroundTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun windowColor() = (rule.activity.window.decorView.background as ColorDrawable).color

    @Test fun pureBlack_paintsTheWindowBlack_andFollowsTheTheme() {
        var look by mutableStateOf(Appearance(ThemeMode.DARK, pureBlack = true, dynamicColor = false))
        rule.setContent { LauschigTheme(look) { WindowBackground() } }
        rule.waitForIdle()
        assertEquals(android.graphics.Color.BLACK, windowColor())
        rule.runOnUiThread { look = Appearance(ThemeMode.LIGHT, dynamicColor = false) }
        rule.waitForIdle()
        assertEquals(LauschigLightColors.background.toArgb(), windowColor())
    }
}
