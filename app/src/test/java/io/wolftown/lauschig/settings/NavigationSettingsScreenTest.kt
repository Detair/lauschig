package io.wolftown.lauschig.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.TopLevelTab
import io.wolftown.lauschig.ui.nav.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h1400dp")
class NavigationSettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun moveHideAndStart_callBack_withAccessibleLabels() {
        val calls = mutableListOf<String>()
        val actions = NavigationSettingsActions(
            onMove = { t, by -> calls += "move:$t:$by" }, onVisible = { t, v -> calls += "vis:$t:$v" }, onStart = { calls += "start:$it" },
        )
        rule.setContent { LauschigTheme { NavigationSettingsScreen(TabLayout.DEFAULT, {}, actions) } }
        rule.onNodeWithContentDescription("Move Library up").performClick()
        rule.onNodeWithContentDescription("Move Search down").assertIsNotEnabled() // last row
        rule.onNodeWithContentDescription("Move Home up").assertIsNotEnabled() // first row
        rule.onNodeWithTag("visible:Discover").performClick()
        rule.onNodeWithTag("start:Search").performClick()
        assertEquals(listOf("move:Library:-1", "vis:Discover:false", "start:Search"), calls)
        rule.onNodeWithTag("navReset").assertIsNotEnabled() // already the default
    }

    @Test fun startCannotBeHidden_lastOptionalTabNeither_hiddenTabsAreNoStartChoice() {
        val two = TabLayout.DEFAULT.withHidden(TopLevelTab.Discover, true).withHidden(TopLevelTab.Search, true)
        rule.setContent { LauschigTheme { NavigationSettingsScreen(two, {}, NavigationSettingsActions()) } }
        rule.onNodeWithTag("visible:Start").assertIsNotEnabled()
        rule.onNodeWithTag("visible:Library").assertIsNotEnabled()
        rule.onNodeWithTag("visible:Search").assertIsEnabled() // can come back
        rule.onNodeWithTag("start:Search").assertDoesNotExist()
        rule.onNodeWithTag("start:Library").assertIsEnabled()
        rule.onNodeWithTag("navReset").assertIsEnabled()
    }
}
