package io.wolftown.lauschig.ui.adaptive

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real breakpoints, from the window size Robolectric configures: rail from 600 dp, two panes from 840 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WindowLayoutTest {
    @get:Rule val rule = createComposeRule()

    private fun measure(): Pair<Boolean, Boolean> {
        var rail = false
        var twoPane = false
        rule.setContent {
            rail = rememberNavigationRail()
            twoPane = rememberPaneDirective().isTwoPane
        }
        rule.waitForIdle()
        return rail to twoPane
    }

    @Config(qualifiers = "w411dp-h891dp") @Test fun phone_bottomBar_onePane() = assertEquals(false to false, measure())
    @Config(qualifiers = "w599dp-h900dp") @Test fun justBelowMedium_bottomBar() = assertEquals(false to false, measure())
    @Config(qualifiers = "w600dp-h900dp") @Test fun medium_rail_onePane() = assertEquals(true to false, measure())
    @Config(qualifiers = "w800dp-h1280dp") @Test fun tabletPortrait_rail_onePane() = assertEquals(true to false, measure())
    @Config(qualifiers = "w839dp-h900dp") @Test fun justBelowExpanded_onePane() = assertEquals(true to false, measure())
    @Config(qualifiers = "w840dp-h900dp") @Test fun expanded_rail_twoPanes() = assertEquals(true to true, measure())
    @Config(qualifiers = "w1280dp-h800dp") @Test fun tabletLandscape_rail_twoPanes() = assertEquals(true to true, measure())
}
