package io.wolftown.lauschig.ui.stats

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.wolftown.lauschig.settings.DarkThemeTone
import io.wolftown.lauschig.ui.colorSchemeFor
import io.wolftown.lauschig.ui.theme.contrastRatio
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chart's bars keep 3:1 against the page (graphics contrast) in every scheme Lauschig uses, the accent too. */
class BarColorTest {
    private val dynamicLight = lightColorScheme(primary = Color(0xFF3F5F90), outline = Color(0xFFC0C0C8)) // an outline too light
    private val dynamicDark = darkColorScheme(primary = Color(0xFFAAC7FF))

    /** Lauschig's own schemes: light, night blue, night blue in pure black, the fireside, the fireside in pure black. */
    private val own = listOf(
        Triple(false, false, DarkThemeTone.NIGHT_BLUE), Triple(true, false, DarkThemeTone.NIGHT_BLUE), Triple(true, true, DarkThemeTone.NIGHT_BLUE),
        Triple(true, false, DarkThemeTone.WARM_AMBER), Triple(true, true, DarkThemeTone.WARM_AMBER),
    ).map { (dark, black, tone) ->
        colorSchemeFor(dark = dark, pureBlack = black, dynamicColor = false, sdkInt = 35, tone = tone) { if (it) dynamicDark else dynamicLight }
    }

    @Test fun plainBarsAndTheCurrentBar_keep3to1_onTheSurface() {
        val schemes = own + listOf(dynamicLight, dynamicDark)
        for (scheme in schemes) {
            val bar = statsBarColor(scheme)
            assertTrue("bar ${contrastRatio(bar, scheme.surface)}", contrastRatio(bar, scheme.surface) >= 3.0)
        }
        for (scheme in own) {
            assertTrue("primary ${contrastRatio(scheme.primary, scheme.surface)}", contrastRatio(scheme.primary, scheme.surface) >= 3.0)
        }
    }

    @Test fun theStartCardsSparkline_keeps3to1_onTheCardsContainer() {
        val schemes = own + listOf(dynamicLight, dynamicDark)
        for (scheme in schemes) {
            val card = scheme.surfaceContainer
            val bar = statsBarColor(scheme, card)
            assertTrue("bar ${contrastRatio(bar, card)}", contrastRatio(bar, card) >= 3.0)
        }
        for (scheme in own) {
            assertTrue("primary ${contrastRatio(scheme.primary, scheme.surfaceContainer)}", contrastRatio(scheme.primary, scheme.surfaceContainer) >= 3.0)
        }
    }
}
