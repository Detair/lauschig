package io.wolftown.lauschig.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSchemeTest {
    private val dynamicDark = darkColorScheme(primary = Color(0xFF00FF00), surfaceContainer = Color(0xFF223322))
    private val dynamicLight = lightColorScheme(primary = Color(0xFF008800))
    private fun dynamic(dark: Boolean) = if (dark) dynamicDark else dynamicLight

    @Test fun light_ignoresPureBlack() {
        assertSame(LauschigLightColors, colorSchemeFor(dark = false, pureBlack = true, dynamicColor = false, sdkInt = 35, dynamic = ::dynamic))
    }

    @Test fun dark_withoutPureBlack_isTheWarmPalette() {
        assertSame(LauschigDarkColors, colorSchemeFor(dark = true, pureBlack = false, dynamicColor = false, sdkInt = 35, dynamic = ::dynamic))
    }

    @Test fun dynamicColor_onlyFromAndroid12() {
        assertSame(dynamicDark, colorSchemeFor(dark = true, pureBlack = false, dynamicColor = true, sdkInt = 31, dynamic = ::dynamic))
        assertSame(LauschigDarkColors, colorSchemeFor(dark = true, pureBlack = false, dynamicColor = true, sdkInt = 30, dynamic = ::dynamic))
        assertSame(dynamicLight, colorSchemeFor(dark = false, pureBlack = false, dynamicColor = true, sdkInt = 35, dynamic = ::dynamic))
    }

    @Test fun pureBlack_blackBackground_liftedContainers() {
        for (base in listOf(false, true)) {
            val s = colorSchemeFor(dark = true, pureBlack = true, dynamicColor = base, sdkInt = 35, dynamic = ::dynamic)
            assertEquals(Color.Black, s.background)
            assertEquals(Color.Black, s.surface)
            assertEquals(Color.Black, s.surfaceDim)
            assertEquals(Color.Black, s.surfaceContainerLowest)
            val lifted = listOf(s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            lifted.forEach { assertNotEquals("cards would vanish on black", Color.Black, it) }
            // Each container step stays at least as light as the one below, so elevation still reads.
            lifted.zipWithNext().forEach { (a, b) -> assertTrue(b.luminance() >= a.luminance()) }
            // Darker than the regular dark scheme's containers: it is meant for the dark bedroom.
            val regular = colorSchemeFor(dark = true, pureBlack = false, dynamicColor = base, sdkInt = 35, dynamic = ::dynamic)
            assertTrue(s.surfaceContainer.luminance() < regular.surfaceContainer.luminance())
            // Text colors stay as they are.
            assertEquals(regular.onSurface, s.onSurface)
            assertEquals(regular.primary, s.primary)
        }
    }
}
