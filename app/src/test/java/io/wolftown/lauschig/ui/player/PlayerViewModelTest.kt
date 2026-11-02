package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.detail.ChapterRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

private class RecordingPlayer : PlayerController {
    override val state = MutableStateFlow(PlayerUiState())
    val calls = mutableListOf<String>()
    override fun play(serverId: String, itemId: String, startSec: Double?) { calls += "play" }
    override fun togglePlayPause() { calls += "toggle" }
    override fun pause() {}
    override fun seekBy(deltaSec: Int) { calls += "seekBy:$deltaSec" }
    override fun seekTo(globalSec: Double) { calls += "seekTo:$globalSec" }
    override fun seekToChapter(index: Int) { calls += "chapter:$index" }
    override fun setSpeed(speed: Float) { calls += "speed:$speed" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val player = RecordingPlayer()
    private val chapters = listOf(
        ChapterRow(0, "One", 100.0, false),
        ChapterRow(1, "Two", 100.0, true),
        ChapterRow(2, "Three", 100.0, false),
    )

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    private val viewModels = TestViewModels()
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(PlayerViewModel(player) { flowOf(chapters.map { ChapterInfo(it.title, it.index * 100.0, it.index * 100.0 + 100.0) }) })

    private fun playing(position: Double, chapter: Int = 1) = PlayerUiState(
        itemId = "b1", title = "Book", positionSec = position, durationSec = 300.0,
        chapterIndex = chapter, chapterTitle = "Two", chapterStartSec = chapter * 100.0, chapterEndSec = chapter * 100.0 + 100.0,
    )

    @Test fun chapterProgress_isRelativeToChapter() = runTest {
        player.state.value = playing(125.0)
        val s = vm().state.first { it.itemId == "b1" && it.chapters.isNotEmpty() }
        assertEquals(0.25f, s.chapterProgress, 0.001f)
        assertEquals(25.0, s.chapterPositionSec, 0.001)
        assertEquals("00:25", s.chapterElapsedText)
        assertEquals("01:40", s.chapterTotalText)
        assertEquals("02:05", s.bookElapsedText)
        assertEquals("05:00", s.bookTotalText)
    }

    @Test fun speedPresets_applyToController() = runTest {
        val v = vm()
        for (p in listOf(1.0f, 1.25f, 1.5f, 2.0f)) v.setSpeed(p)
        v.setSpeed(1.2999f)
        assertEquals(listOf("speed:1.0", "speed:1.25", "speed:1.5", "speed:2.0", "speed:1.3"), player.calls)
    }

    @Test fun previousChapter_restartsCurrentWhenMoreThan3sIn_elseGoesBack() = runTest {
        player.state.value = playing(150.0)
        val v = vm(); v.state.first { it.chapters.isNotEmpty() }
        v.previousChapter()
        player.state.value = playing(102.0)
        v.state.first { it.chapterPositionSec < 3 }
        v.previousChapter()
        v.nextChapter()
        assertEquals(listOf("chapter:1", "chapter:0", "chapter:2"), player.calls)
    }

    @Test fun sliderSeek_usesAbsolutePosition() = runTest {
        player.state.value = playing(125.0)
        val v = vm(); v.state.first { it.chapters.isNotEmpty() }
        v.seekInChapter(0.75f)
        v.seekInChapter(1.0f)
        assertEquals(listOf("seekTo:175.0", "seekTo:199.0"), player.calls)
    }
}
