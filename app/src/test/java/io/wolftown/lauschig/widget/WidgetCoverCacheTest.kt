package io.wolftown.lauschig.widget

import io.wolftown.lauschig.auto.CoverRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCoverCacheTest {
    private var now = 0L
    private val loads = mutableListOf<CoverRef>()
    private var available = mutableMapOf<CoverRef, String>()
    private val cache = WidgetCoverCache<String>(missRetryMs = 60_000, clock = { now })
    private val book = CoverRef("0123456789abcdef", "li_1", 3)
    private val episode = CoverRef("0123456789abcdef", "pod_1~ep_2", 1)

    private fun get(ref: CoverRef?) = cache.get(ref) { loads += it; available[it] }

    @Test fun sameCover_isDecodedOnce_acrossPositionUpdates() {
        available[book] = "bitmap"
        repeat(5) { assertEquals("bitmap", get(book)) }
        assertEquals(listOf(book), loads)
    }

    @Test fun anotherTitleOrVersion_isLoadedAgain() {
        available[book] = "a"
        available[book.copy(version = 4)] = "b"
        get(book)
        assertEquals("b", get(book.copy(version = 4)))
        assertEquals(2, loads.size)
        assertEquals(null, get(null))
    }

    @Test fun aMiss_isAskedAgainOnlyAfterAWhile() {
        get(book)
        get(book)
        assertEquals(1, loads.size)
        now = 60_001
        get(book)
        assertEquals(2, loads.size)
    }

    @Test fun arrival_ofTheWidgetsCover_reloadsIt_othersAreIgnored() {
        get(book)
        assertFalse(cache.arrived("0123456789abcdef", "li_other"))
        assertFalse(cache.arrived("fedcba9876543210", "li_1"))
        assertTrue(cache.arrived("0123456789abcdef", "li_1"))
        available[book] = "now there"
        assertEquals("now there", get(book))
        assertEquals(2, loads.size)
    }

    @Test fun arrival_matchesAnEpisodeByItsPodcast() {
        get(episode)
        assertTrue(cache.arrived("0123456789abcdef", "pod_1"))
    }

    @Test fun arrival_beforeAnyCoverWasShown_isIgnored() {
        assertFalse(cache.arrived("0123456789abcdef", "li_1"))
    }
}
