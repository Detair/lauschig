package io.wolftown.lauschig.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwatchSelectionTest {
    private val grey = SwatchOption(0xFF8A8580.toInt(), 30)
    private val darkGrey = SwatchOption(0xFF3A3530.toInt(), 20)
    private val red = SwatchOption(0xFFD03030.toInt(), 800)
    private val dominant = SwatchOption(0xFFC02828.toInt(), 900)

    @Test fun aSmallMutedArea_doesNotWin_overTheCoversCharacter() {
        // Grey and dark grey are 3 % and 2 % of 1000 pixels: details. The vibrant red is the cover.
        assertEquals(red.rgb, pickSwatch(grey, darkGrey, red, dominant, total = 1000))
    }

    @Test fun aCalmCover_keepsItsMutedTone() {
        val bigGrey = grey.copy(population = 400)
        assertEquals(bigGrey.rgb, pickSwatch(bigGrey, darkGrey, red, dominant, total = 1000))
    }

    @Test fun order_isMutedDarkMutedVibrantDominant() {
        val m = SwatchOption(1, 100)
        val dm = SwatchOption(2, 100)
        val v = SwatchOption(3, 100)
        val d = SwatchOption(4, 100)
        assertEquals(1, pickSwatch(m, dm, v, d, 1000))
        assertEquals(2, pickSwatch(null, dm, v, d, 1000))
        assertEquals(3, pickSwatch(null, null, v, d, 1000))
        assertEquals(4, pickSwatch(null, null, null, d, 1000))
    }

    @Test fun allTooSmall_fallsBackToTheDominant_noneAtAll_isNull() {
        assertEquals(dominant.rgb, pickSwatch(grey, darkGrey, null, dominant.copy(population = 10), total = 10_000))
        assertNull(pickSwatch(null, null, null, null, 0))
    }

    @Test fun key_carriesTheCoverVersion() {
        assertNotEquals(swatchKey("/files/cover.jpg", 1), swatchKey("/files/cover.jpg", 2))
        assertEquals(swatchKey("https://abs/api/items/1/cover?ts=5", 5), swatchKey("https://abs/api/items/1/cover?ts=5", 5))
    }

    @Test fun cache_keepsSwatches_andForgetsFailuresAfterAWhile() {
        var now = 0L
        val cache = SwatchCache(capacity = 2, failureTtlMs = 60_000, now = { now })
        cache.put("a", Color.Red)
        cache.putFailure("b")
        assertEquals(Color.Red, cache.get("a"))
        assertEquals("a failure is remembered as no swatch", Color.Unspecified, cache.get("b"))
        now = 60_001
        assertNull("and tried again later", cache.get("b"))
        assertEquals("a swatch does not expire", Color.Red, cache.get("a"))
    }

    @Test fun cache_isBounded_leastRecentlyUsedGoesFirst() {
        val cache = SwatchCache(capacity = 2, now = { 0L })
        cache.put("a", Color.Red)
        cache.put("b", Color.Green)
        cache.get("a")
        cache.put("c", Color.Blue)
        assertNull(cache.get("b"))
        assertEquals(Color.Red, cache.get("a"))
        assertEquals(Color.Blue, cache.get("c"))
    }
}
