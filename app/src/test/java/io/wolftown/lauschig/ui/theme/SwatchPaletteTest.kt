package io.wolftown.lauschig.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Target
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [swatchOf] on a synthetic Palette: the population decides, not just Palette's muted target. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SwatchPaletteTest {
    private val vividRed = 0xFFD8262A.toInt()
    private val mutedGrey = 0xFFA3876B.toInt() // a greyish brown: saturation about 0.2, lightness about 0.53

    /** A palette as `Palette.from(bitmap)` builds it (the same targets), from hand-made swatches. */
    private fun paletteOf(vararg swatches: Palette.Swatch): Palette = Palette.Builder(swatches.toList()).apply {
        listOf(Target.LIGHT_VIBRANT, Target.VIBRANT, Target.DARK_VIBRANT, Target.LIGHT_MUTED, Target.MUTED, Target.DARK_MUTED).forEach { addTarget(it) }
    }.generate()

    @Test fun vividCover_withASmallGreyPatch_tintsRed() {
        val palette = paletteOf(Palette.Swatch(vividRed, 950), Palette.Swatch(mutedGrey, 20))
        // Palette itself would offer the grey as the muted swatch …
        assertEquals(mutedGrey, palette.mutedSwatch?.rgb)
        // … but it is 2 % of the cover.
        assertEquals(Color(vividRed), swatchOf(palette))
    }

    @Test fun calmCover_tintsInItsMutedTone() {
        val palette = paletteOf(Palette.Swatch(vividRed, 300), Palette.Swatch(mutedGrey, 700))
        assertEquals(Color(mutedGrey), swatchOf(palette))
    }
}
