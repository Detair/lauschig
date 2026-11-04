package io.wolftown.lauschig.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.wolftown.lauschig.ui.LauschigDarkColors
import io.wolftown.lauschig.ui.LauschigLightColors
import io.wolftown.lauschig.ui.withPureBlack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverTintTest {
    /** Covers from loud to dull: the tint must keep body text readable for all of them. */
    private val swatches = listOf(
        Color(0xFFE53935), Color(0xFFFFEB3B), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFF8E24AA),
        Color.Black, Color.White, Color(0xFF808080), Color(0xFF6D5A4A), Color(0xFF2E4057),
    )

    private val schemes: List<Pair<String, ColorScheme>> = listOf(
        "baseline light" to lightColorScheme(),
        "baseline dark" to darkColorScheme(),
        "fallback light" to LauschigLightColors,
        "fallback dark" to LauschigDarkColors,
        "pure black" to LauschigDarkColors.withPureBlack(),
    )

    @Test fun contrastRatio_matchesWcag() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color(0xFF777777), Color(0xFF777777)), 0.001)
        // Order does not matter.
        assertEquals(contrastRatio(Color.White, Color(0xFF336699)), contrastRatio(Color(0xFF336699), Color.White), 1e-9)
    }

    @Test fun tint_keepsBodyTextAtWcagAA_inLightAndDarkThemes() {
        for ((name, scheme) in schemes) {
            for (base in listOf(scheme.surface, scheme.surfaceContainerHigh)) {
                for (swatch in swatches) {
                    val tinted = coverTintOver(base, swatch, scheme)
                    listOf(scheme.onSurface, scheme.onSurfaceVariant).forEach { text ->
                        val ratio = contrastRatio(text, tinted)
                        val wanted = minOf(MIN_TEXT_CONTRAST, contrastRatio(text, base))
                        assertTrue("$name: $swatch over $base gives $ratio", ratio >= wanted - 1e-6)
                    }
                }
            }
        }
    }

    @Test fun tint_actuallyTints_aMutedCover() {
        val muted = Color(0xFF6D5A4A)
        for ((name, scheme) in schemes) {
            val tinted = coverTintOver(scheme.surface, muted, scheme)
            assertNotEquals("$name: no tint at all", scheme.surface, tinted)
            // Still mostly the surface: closer to it than to the swatch.
            assertTrue(name, distance(tinted, scheme.surface) < distance(tinted, muted))
        }
    }

    @Test fun lightTheme_staysLight_darkTheme_staysDark() {
        val loud = Color(0xFFE53935)
        assertTrue(coverTintOver(LauschigLightColors.surface, loud, LauschigLightColors).luminance() > 0.5f)
        assertTrue(coverTintOver(LauschigDarkColors.surface, loud, LauschigDarkColors).luminance() < 0.2f)
        val black = LauschigDarkColors.withPureBlack()
        assertTrue(coverTintOver(black.surface, loud, black).luminance() < 0.1f)
    }

    @Test fun tintStrength_dependsOnTheTheme() {
        assertTrue(tintFraction(LauschigLightColors) in 0.18f..0.35f)
        assertTrue(tintFraction(LauschigDarkColors) in 0.18f..0.35f)
        assertTrue(tintFraction(LauschigDarkColors.withPureBlack()) in 0.18f..0.35f)
        // Pure black is for the dark bedroom: the weakest tint.
        assertTrue(tintFraction(LauschigDarkColors.withPureBlack()) < tintFraction(LauschigDarkColors))
    }

    @Test fun isDark_followsTheSurface() {
        assertTrue(LauschigDarkColors.isDark)
        assertTrue(LauschigDarkColors.withPureBlack().isDark)
        assertFalse(LauschigLightColors.isDark)
        assertTrue(LauschigDarkColors.withPureBlack().isPureBlack)
        assertFalse(LauschigDarkColors.isPureBlack)
    }

    @Test fun calmSwatch_tamesLoudColours_keepsTheHue() {
        val calm = calmSwatch(Color(0xFFFF0000))
        val (h, s, _) = calm.toHsl()
        assertEquals(0f, h, 1f)
        assertTrue(s <= 0.55f)
        // A grey stays grey.
        val (_, greyS, _) = calmSwatch(Color(0xFF808080)).toHsl()
        assertEquals(0f, greyS, 0.01f)
    }

    @Test fun controls_keepPrimary_onlyWhereItReads3to1() {
        val scheme = lightColorScheme(primary = Color(0xFF415F91), onSurface = Color(0xFF1A1C20))
        assertEquals(scheme.primary, scheme.controlColorOn(Color(0xFFF0F2F8)))
        // A tint close to primary itself: the control falls back to the text colour, which reads.
        val close = Color(0xFF5A75A6)
        assertEquals(scheme.onSurface, scheme.controlColorOn(close))
        assertTrue(contrastRatio(scheme.onSurface, close) >= MIN_CONTROL_CONTRAST)
        // Every tint of the tested swatches leaves the controls readable one way or the other.
        for ((name, s) in schemes) for (swatch in swatches) {
            val bg = coverTintOver(s.surfaceContainerHigh, swatch, s)
            assertTrue("$name $swatch", contrastRatio(s.controlColorOn(bg), bg) >= MIN_CONTROL_CONTRAST)
        }
    }

    private fun distance(a: Color, b: Color): Float =
        (a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) + (a.blue - b.blue) * (a.blue - b.blue)
}
