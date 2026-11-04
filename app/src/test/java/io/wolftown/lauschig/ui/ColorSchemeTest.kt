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
import io.wolftown.lauschig.ui.theme.contrastRatio

class ColorSchemeTest {
    private val dynamicDark = darkColorScheme(primary = Color(0xFF00FF00), surfaceContainer = Color(0xFF223322))
    private val dynamicLight = lightColorScheme(primary = Color(0xFF008800))
    private fun dynamic(dark: Boolean) = if (dark) dynamicDark else dynamicLight

    @Test fun light_ignoresPureBlack() {
        assertSame(LauschigLightColors, colorSchemeFor(dark = false, pureBlack = true, dynamicColor = false, sdkInt = 35, dynamic = ::dynamic))
    }

    @Test fun fallback_isTheCalmPalette_fromTheBrief() {
        assertEquals(Color(0xFF18213A), LauschigDarkColors.background)
        assertEquals(Color(0xFF263257), LauschigDarkColors.surfaceContainerHigh)
        assertEquals(Color(0xFFF0B44C), LauschigDarkColors.primary)
        assertEquals(Color(0xFFEEF1F6), LauschigLightColors.background)
        assertEquals(Color(0xFF8A5A00), LauschigLightColors.primary)
        assertEquals(Color(0xFF5B6B8C), LauschigLightColors.secondary)
        assertEquals(Color(0xFF4F7A64), LauschigLightColors.tertiary)
    }

    @Test fun fallback_onColours_keepWcagAA() {
        for (s in listOf(LauschigLightColors, LauschigDarkColors)) {
            listOf(
                s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
                s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
                s.onTertiary to s.tertiary, s.onTertiaryContainer to s.tertiaryContainer,
                s.onError to s.error, s.onErrorContainer to s.errorContainer,
                s.onSurface to s.surface, s.onSurfaceVariant to s.surface, s.onSurfaceVariant to s.surfaceVariant,
                s.onSurfaceVariant to s.surfaceContainerHighest, s.inverseOnSurface to s.inverseSurface, s.primary to s.surface,
            ).forEach { (fg, bg) -> assertTrue("$fg on $bg", contrastRatio(fg, bg) >= 4.5) }
        }
    }

    @Test fun fallback_setsEveryRole_noBaselinePurpleLeft() {
        val baseline = listOf(lightColorScheme(), darkColorScheme())
        for ((s, base) in listOf(LauschigLightColors, LauschigDarkColors).zip(baseline)) {
            listOf(
                // (The light scheme's lowest container is white, as the baseline's is.)
                s.surfaceContainerLow to base.surfaceContainerLow,
                s.surfaceContainer to base.surfaceContainer, s.surfaceContainerHigh to base.surfaceContainerHigh,
                s.surfaceContainerHighest to base.surfaceContainerHighest, s.surfaceBright to base.surfaceBright,
                s.surfaceDim to base.surfaceDim, s.outlineVariant to base.outlineVariant, s.inversePrimary to base.inversePrimary,
                s.primaryFixed to base.primaryFixed, s.secondaryFixed to base.secondaryFixed, s.tertiaryFixed to base.tertiaryFixed,
            ).forEach { (mine, theirs) -> assertNotEquals(theirs, mine) }
        }
        // The containers step one way: lighter with height in dark, darker in light (lowest = white).
        val dark = LauschigDarkColors.run { listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
        dark.zipWithNext().forEach { (a, b) -> assertTrue(b.luminance() > a.luminance()) }
        val light = LauschigLightColors.run { listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
        light.zipWithNext().forEach { (a, b) -> assertTrue(b.luminance() < a.luminance()) }
    }

    @Test fun dark_withoutPureBlack_isTheFallbackPalette() {
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
