package io.wolftown.lauschig.ui.common

import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.sync.PositionJump
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class KeyPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val seeks = mutableListOf<Double>()
    val plays = mutableListOf<Triple<String, String, Double?>>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { plays += Triple(serverId, itemId, startSec) }
    override fun togglePlayPause() {}
    override fun seekBy(deltaSec: Int) {}
    override fun seekTo(globalSec: Double) { seeks += globalSec }
    override fun seekToChapter(index: Int) {}
    override fun setSpeed(speed: Float) {}
}

/** The player state and [PositionJump] both carry the playable key; for episodes that is `itemId~episodeId`. */
class PositionJumpKeyTest {
    private val key = PlayableKey.episode("pod", "ep1")
    private val jump = PositionJump(key, 100.0, 500.0)

    @Test fun jumpToShow_matchesLoadedEpisodeKey_notThePodcast() {
        assertEquals(jump, jumpToShow(listOf(jump), key))
        assertNull(jumpToShow(listOf(jump), "pod"))
    }

    @Test fun undo_loadedEpisode_seeks_otherwisePlaysTheKey() {
        val player = KeyPlayer()
        undoPositionJump(jump, player, PlayerUiState(itemId = key), "srv")
        assertEquals(listOf(100.0), player.seeks)
        undoPositionJump(jump, player, PlayerUiState(itemId = "pod"), "srv")
        assertEquals(listOf(Triple("srv", key, 100.0)), player.plays)
    }
}
