package io.wolftown.lauschig.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayableKeyTest {
    @Test fun episodeKey_roundTrips() {
        val key = PlayableKey.episode("li_pod1", "ep_2")
        assertEquals("li_pod1~ep_2", key)
        assertEquals(PlayableKey.Parsed("li_pod1", "ep_2"), PlayableKey.parse(key))
    }

    @Test fun bookKey_hasNoEpisode() {
        val p = PlayableKey.parse("li_book")
        assertEquals("li_book", p.libraryItemId)
        assertNull(p.episodeId)
    }

    @Test fun parse_splitsOnFirstSeparator_only() {
        assertEquals(PlayableKey.Parsed("a", "b~c"), PlayableKey.parse("a~b~c"))
    }
}
