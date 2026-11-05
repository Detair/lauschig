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
import io.wolftown.lauschig.settings.DarkThemeTone
import io.wolftown.lauschig.ui.theme.contrastRatio
import io.wolftown.lauschig.ui.theme.toHsl

class ColorSchemeTest {
    private val dynamicDark = darkColorScheme(primary = Color(0xFF00FF00), surfaceContainer = Color(0xFF223322))
    private val dynamicLight = lightColorScheme(primary = Color(0xFF008800))
    private fun dynamic(dark: Boolean) = if (dark) dynamicDark else dynamicLight

    /** Lauschig's own palettes: light, night blue, fireside (warm amber). */
    private val palettes = listOf(LauschigLightColors, LauschigDarkColors, LauschigWarmDarkColors)

    @Test fun light_ignoresPureBlack() {
        assertSame(LauschigLightColors, colorSchemeFor(dark = false, pureBlack = true, dynamicColor = false, sdkInt = 35, dynamic = ::dynamic))
    }

    @Test fun fallback_isTheCalmPalette_fromTheBrief() {
        assertEquals(Color(0xFF18213A), LauschigDarkColors.background)
        assertEquals(Color(0xFF263257), LauschigDarkColors.surfaceContainerHigh)
        assertEquals(Color(0xFFF0B44C), LauschigDarkColors.primary)
        assertEquals(Color(0xFFEEF1F6), LauschigLightColors.background)
        assertEquals(Color(0xFF8A5A00), LauschigLightColors.primary)
        // Slate and moss as they are in the dark containers, one shade darker as light text roles (same hue).
        assertEquals(Color(0xFF5B6B8C), LauschigDarkColors.secondaryContainer)
        assertEquals(Color(0xFF4F7A64), LauschigDarkColors.tertiaryContainer)
        assertEquals(Color(0xFF5B6B8C).toHsl().first, LauschigLightColors.secondary.toHsl().first, 3f)
        assertEquals(Color(0xFF4F7A64).toHsl().first, LauschigLightColors.tertiary.toHsl().first, 3f)
    }

    @Test fun fallback_textRoles_keepWcagAA_onTheSurfaceAndEveryContainer() {
        for (s in palettes + LauschigWarmDarkColors.withPureBlack()) {
            val backgrounds = listOf(s.surface, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            for (text in listOf(s.primary, s.secondary, s.tertiary, s.onSurface, s.onSurfaceVariant, s.error)) {
                backgrounds.forEach { bg -> assertTrue("$text on $bg: ${contrastRatio(text, bg)}", contrastRatio(text, bg) >= 4.5) }
            }
        }
    }

    @Test fun fallback_colourContainers_standOutFromTheSurface() {
        for (s in palettes) {
            // secondaryContainer is also the slider's inactive track.
            listOf(s.primaryContainer, s.secondaryContainer, s.tertiaryContainer).forEach {
                assertTrue("$it on ${s.surface}: ${contrastRatio(it, s.surface)}", contrastRatio(it, s.surface) >= 1.3)
            }
        }
    }

    @Test fun fallback_onColours_keepWcagAA() {
        for (s in palettes) {
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
        val baseline = listOf(lightColorScheme(), darkColorScheme(), darkColorScheme())
        for ((s, base) in palettes.zip(baseline)) {
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
        for (darkScheme in listOf(LauschigDarkColors, LauschigWarmDarkColors)) {
            val dark = darkScheme.run { listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
            dark.zipWithNext().forEach { (a, b) -> assertTrue(b.luminance() > a.luminance()) }
        }
        val light = LauschigLightColors.run { listOf(surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest) }
        light.zipWithNext().forEach { (a, b) -> assertTrue(b.luminance() < a.luminance()) }
    }

    @Test fun warmDark_isTheFiresidePalette_fromThePlan() {
        LauschigWarmDarkColors.run {
            assertEquals(Color(0xFF161311), background)
            assertEquals(Color(0xFF161311), surface)
            assertEquals(Color(0xFF231C18), surfaceContainer)
            assertEquals(Color(0xFF2B231F), surfaceContainerHigh)
            assertEquals(Color(0xFFF5BA42), primary)
            assertEquals(Color(0xFFF4ECE1), onSurface)
            assertEquals(Color(0xFFB8ACA0), onSurfaceVariant)
        }
        // Warm, not blue: every surface leans red/yellow (red channel above blue).
        LauschigWarmDarkColors.run { listOf(surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceVariant) }
            .forEach { assertTrue("$it is not warm", it.red > it.blue) }
    }

    /** Controls (outlines, switch tracks, the accent) keep 3:1 against the page and every container (WCAG 1.4.11). */
    @Test fun fallback_controls_keep3to1_onTheSurfaceAndEveryContainer() {
        for (s in palettes + LauschigWarmDarkColors.withPureBlack()) {
            val backgrounds = listOf(s.surface, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)
            for (control in listOf(s.outline, s.primary)) {
                backgrounds.forEach { bg -> assertTrue("$control on $bg: ${contrastRatio(control, bg)}", contrastRatio(control, bg) >= 3.0) }
            }
        }
    }

    @Test fun warmTone_inDark_isTheFiresidePalette_alsoInsteadOfDynamicColors() {
        for (dynamicColor in listOf(false, true)) {
            assertSame(
                LauschigWarmDarkColors,
                colorSchemeFor(dark = true, pureBlack = false, dynamicColor = dynamicColor, sdkInt = 35, tone = DarkThemeTone.WARM_AMBER, dynamic = ::dynamic),
            )
        }
    }

    @Test fun warmTone_leavesTheLightThemeAlone() {
        assertSame(LauschigLightColors, colorSchemeFor(dark = false, pureBlack = false, dynamicColor = false, sdkInt = 35, tone = DarkThemeTone.WARM_AMBER, dynamic = ::dynamic))
        assertSame(dynamicLight, colorSchemeFor(dark = false, pureBlack = true, dynamicColor = true, sdkInt = 35, tone = DarkThemeTone.WARM_AMBER, dynamic = ::dynamic))
    }

    @Test fun nightBlueTone_isTheDefault() {
        assertSame(LauschigDarkColors, colorSchemeFor(dark = true, pureBlack = false, dynamicColor = false, sdkInt = 35, tone = DarkThemeTone.NIGHT_BLUE, dynamic = ::dynamic))
        assertSame(dynamicDark, colorSchemeFor(dark = true, pureBlack = false, dynamicColor = true, sdkInt = 35, tone = DarkThemeTone.NIGHT_BLUE, dynamic = ::dynamic))
    }

    @Test fun warmTone_withPureBlack_blackPage_warmContainers_sameText() {
        val s = colorSchemeFor(dark = true, pureBlack = true, dynamicColor = true, sdkInt = 35, tone = DarkThemeTone.WARM_AMBER, dynamic = ::dynamic)
        assertEquals(Color.Black, s.background)
        assertEquals(Color.Black, s.surface)
        assertEquals(LauschigWarmDarkColors.onSurface, s.onSurface)
        assertEquals(LauschigWarmDarkColors.primary, s.primary)
        // The cards keep the fireside's warmth, only darker.
        listOf(s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest).forEach {
            assertNotEquals(Color.Black, it)
            assertTrue("$it is not warm", it.red > it.blue)
        }
        assertTrue(s.surfaceContainer.luminance() < LauschigWarmDarkColors.surfaceContainer.luminance())
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
