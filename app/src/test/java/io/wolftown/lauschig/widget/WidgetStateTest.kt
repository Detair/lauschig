package io.wolftown.lauschig.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    private fun snap(
        index: Int = 0,
        positionMs: Long = 0,
        durations: List<Long> = listOf(60_000, 60_000, 60_000),
        currentDuration: Long = -1,
        playWhenReady: Boolean = true,
        state: Int = 3,
        subtitle: String? = null,
        artist: String? = "Autorin",
        serverId: String? = "0123456789abcdef",
        key: String? = "li_1",
    ) = WidgetPlayerSnapshot(serverId, key, "Titel", subtitle, artist, 7, playWhenReady, state, index, positionMs, durations, currentDuration)

    @Test fun position_isBookWide_acrossFiles() {
        val s = WidgetMapping.map(snap(index = 2, positionMs = 15_000), null)
        assertEquals(135_000, s.positionMs)
        assertEquals(180_000, s.durationMs)
        assertEquals(750, s.progressPermille)
    }

    @Test fun currentFileDuration_fromThePlayer_winsOverTheExtra() {
        val s = WidgetMapping.map(snap(index = 0, positionMs = 10_000, durations = listOf(0, 60_000), currentDuration = 40_000), null)
        assertEquals(100_000, s.durationMs)
        assertEquals(10_000, s.positionMs)
    }

    @Test fun subtitle_isTheChapter_elseTheAuthor() {
        assertEquals("Kapitel 3", WidgetMapping.map(snap(subtitle = "Kapitel 3"), null).subtitle)
        assertEquals("Autorin", WidgetMapping.map(snap(subtitle = " "), null).subtitle)
        assertEquals(null, WidgetMapping.map(snap(subtitle = null, artist = null), null).subtitle)
    }

    @Test fun playing_isTheIntent_bufferingCounts_endedDoesNot() {
        assertTrue(WidgetMapping.map(snap(state = 2), null).playing)
        assertTrue(WidgetMapping.map(snap(state = 3), null).playing)
        assertFalse(WidgetMapping.map(snap(state = 4), null).playing)
        assertFalse(WidgetMapping.map(snap(state = 1), null).playing)
        assertFalse(WidgetMapping.map(snap(playWhenReady = false), null).playing)
    }

    @Test fun emptyPlayer_keepsTheLastTitle_paused() {
        val before = WidgetMapping.map(snap(index = 1, positionMs = 5_000), null)
        val after = WidgetMapping.map(snap(serverId = null, key = null), before)
        assertEquals(before.copy(playing = false), after)
        assertTrue(after.hasTitle)
        assertFalse(WidgetMapping.map(snap(serverId = null, key = null), null).hasTitle)
    }

    @Test fun coverRef_carriesTheVersion() {
        val ref = WidgetMapping.map(snap(), null).coverRef()!!
        assertEquals("li_1", ref.key)
        assertEquals(7, ref.version)
        assertEquals(null, WidgetState().coverRef())
    }

    @Test fun position_isClampedToTheBook() {
        assertEquals(180_000, WidgetMapping.map(snap(index = 2, positionMs = 99_000), null).positionMs)
    }
}

class WidgetThrottleTest {
    private val playing = WidgetState("0123456789abcdef", "li_1", "Titel", "Kapitel 1", 0, playing = true, positionMs = 0, durationMs = 600_000)

    @Test fun first_andStructuralChanges_goOutAtOnce() {
        val t = WidgetThrottle()
        assertTrue(t.offer(playing, 0))
        assertTrue(t.offer(playing.copy(subtitle = "Kapitel 2", positionMs = 1_000), 1_000))
        assertTrue(t.offer(playing.copy(subtitle = "Kapitel 2", playing = false, positionMs = 2_000), 2_000))
    }

    @Test fun positionWhilePlaying_atMostEvery30s() {
        val t = WidgetThrottle()
        t.offer(playing, 0)
        assertFalse(t.offer(playing.copy(positionMs = 1_000), 1_000))
        assertFalse(t.offer(playing.copy(positionMs = 29_000), 29_000))
        assertTrue(t.offer(playing.copy(positionMs = 30_000), 30_000))
        assertFalse(t.offer(playing.copy(positionMs = 31_000), 31_000))
    }

    @Test fun positionWhilePaused_goesOutAtOnce() {
        val t = WidgetThrottle()
        val paused = playing.copy(playing = false)
        t.offer(paused, 0)
        assertTrue(t.offer(paused.copy(positionMs = 120_000), 500))
    }

    @Test fun sameState_isNotWrittenAgain() {
        val t = WidgetThrottle()
        t.offer(playing, 0)
        assertFalse(t.offer(playing, 60_000))
    }
}
