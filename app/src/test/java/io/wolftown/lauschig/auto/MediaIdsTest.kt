package io.wolftown.lauschig.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    private val s = "0123456789abcdef"

    private fun roundTrip(node: CarNode) = assertEquals(node, MediaIds.parse(MediaIds.of(node)))

    @Test fun fixedNodes_roundTrip() {
        listOf(CarNode.Root, CarNode.Recent, CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries, CarNode.SignIn).forEach(::roundTrip)
        assertEquals("root", MediaIds.of(CarNode.Root))
    }

    @Test fun titles_roundTrip_book_episode_device() {
        roundTrip(CarNode.Title(s, "li_123-abc"))
        roundTrip(CarNode.Title(s, "pod1~ep_2"))
        roundTrip(CarNode.Title("device", "local:0123456789abcdef01234567"))
        assertEquals("title/$s/li_123-abc", MediaIds.of(CarNode.Title(s, "li_123-abc")))
    }

    @Test fun folders_roundTrip_withFreeTextSeriesNames() {
        roundTrip(CarNode.Library("lib_1"))
        roundTrip(CarNode.Library("device"))
        roundTrip(CarNode.Podcast("pod1"))
        roundTrip(CarNode.Series("Die drei ???"))
        roundTrip(CarNode.Series("AC/DC: Live / Teil 2 · Ä"))
        roundTrip(CarNode.Series("100% & mehr"))
    }

    @Test fun malformedIds_areRejected() {
        listOf(
            "", "nope", "title", "title/$s", "title/$s/", "title/abc/item", "title/$s/../x", "title/$s/a/b",
            "title/$s/local:0123456789abcdef01234567", // a device key under a server id
            "title/device/li_1", // a server key under the device
            "title/device/local:xyz", "library/", "library/a b", "podcast/x~y", "series/", "root/x", "continue/1",
        ).forEach { assertNull(it, MediaIds.parse(it)) }
    }

    @Test fun pageSlicing() {
        val list = (0 until 10).toList()
        assertEquals(listOf(0, 1, 2, 3), list.page(0, 4))
        assertEquals(listOf(8, 9), list.page(2, 4))
        assertEquals(emptyList<Int>(), list.page(3, 4))
        assertEquals(list, list.page(0, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), list.page(1, Int.MAX_VALUE)) // no overflow
        assertEquals(emptyList<Int>(), list.page(-1, 4))
        assertEquals(emptyList<Int>(), list.page(0, 0))
    }
}
