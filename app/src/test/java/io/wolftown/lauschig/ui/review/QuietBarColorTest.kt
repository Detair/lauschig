package io.wolftown.lauschig.ui.review

import androidx.compose.ui.graphics.Color
import io.wolftown.lauschig.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The month bars on a slide keep 3:1 against its background and stay quieter than the strongest month where they can. */
class QuietBarColorTest {
    @Test fun keepsThreeToOne_andIsQuieterThanTheContent_whereThereIsRoom() {
        for ((bg, fg) in listOf(
            Color(0xFF8E6B8C) to Color.White, // a mid-tone container with a light "on" colour (Android 16 dynamic)
            Color(0xFFAEDDBD) to Color(0xFF0A3320), // Lauschig's light tertiary container
            Color(0xFF4F7A64) to Color(0xFFCBEBD8), // and its dark one
            Color(0xFF101418) to Color.White,
        )) {
            val bar = quietBarColor(bg, fg)
            assertTrue("$bg", contrastRatio(bar, bg) >= 3.0)
            if (contrastRatio(fg, bg) >= 6.0) assertNotEquals("$bg", fg, bar)
        }
    }

    @Test fun withoutABackground_theContentColour() {
        assertEquals(Color.White, quietBarColor(Color.Unspecified, Color.White))
    }
}
