package io.wolftown.lauschig.ui.player

import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test fun seekSteps_comeFromTheController() = runTest {
        player.state.value = playing(125.0).copy(seekBackSec = 10, seekForwardSec = 60)
        val s = vm().state.first { it.itemId == "b1" }
        assertEquals(10, s.seekBackSec)
        assertEquals(60, s.seekForwardSec)
    }

    @Test fun chapterProgress_isRelativeToChapter() = runTest {
        player.state.value = playing(125.0)
        val s = vm().state.first { it.itemId == "b1" && it.chapters.isNotEmpty() }
        assertEquals(0.25f, s.chapterProgress, 0.001f)
        assertEquals(25.0, s.chapterPositionSec, 0.001)
        assertEquals("00:25", s.chapterElapsedText)
        assertEquals(75.0, s.chapterRemainingSec, 0.001)
        assertEquals(175.0, s.bookRemainingSec, 0.001)
    }

    @Test fun remainingTime_followsTheSetting_andTheToggleStoresTheOther() = runTest {
        val mode = MutableStateFlow(io.wolftown.lauschig.settings.RemainingTime.CHAPTER)
        val stored = mutableListOf<io.wolftown.lauschig.settings.RemainingTime>()
        val v = viewModels.own(
            PlayerViewModel(player, chaptersOf = { flowOf(emptyList()) }, remainingTime = mode, setRemainingTime = { stored += it; mode.value = it }),
        )
        player.state.value = playing(125.0)
        assertEquals(io.wolftown.lauschig.settings.RemainingTime.CHAPTER, v.state.first { it.itemId == "b1" }.remainingTime)
        v.toggleRemainingTime()
        assertEquals(io.wolftown.lauschig.settings.RemainingTime.BOOK, v.state.first { it.remainingTime == io.wolftown.lauschig.settings.RemainingTime.BOOK }.remainingTime)
        v.toggleRemainingTime()
        assertEquals(listOf(io.wolftown.lauschig.settings.RemainingTime.BOOK, io.wolftown.lauschig.settings.RemainingTime.CHAPTER), stored)
    }

    @Test fun authorAndSeries_followTheLoadedTitle() = runTest {
        val metas = mapOf("b1" to PlayerMeta("Autorin", "Saga", "3", coverVersion = 42), "b2" to PlayerMeta("Jemand"))
        val v = viewModels.own(PlayerViewModel(player, metaOf = { flowOf(metas[it]) }) { flowOf(emptyList()) })
        player.state.value = playing(125.0)
        val s = v.state.first { it.itemId == "b1" && it.author != null }
        assertEquals(Triple("Autorin", "Saga", "3"), Triple(s.author, s.seriesName, s.seriesSequence))
        assertEquals("the cover URL carries the version, like the tiles'", 42L, s.coverVersion)
        player.state.value = playing(10.0).copy(itemId = "b2")
        val other = v.state.first { it.itemId == "b2" && it.author == "Jemand" }
        assertEquals(null, other.seriesName)
    }

    @Test fun aNewTitle_isNeverShownWithThePreviousTitlesMeta() = runTest {
        val b2Meta = MutableSharedFlow<PlayerMeta?>() // the new title's row is not read yet
        val v = viewModels.own(PlayerViewModel(player, metaOf = { if (it == "b1") flowOf(PlayerMeta("Autorin", "Saga", "3", coverVersion = 42)) else b2Meta }) { flowOf(emptyList()) })
        val seen = mutableListOf<PlayerScreenState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.state.toList(seen) }
        player.state.value = playing(125.0)
        v.state.first { it.itemId == "b1" && it.coverVersion == 42L }
        player.state.value = playing(10.0).copy(itemId = "b2")
        v.state.first { it.itemId == "b2" }
        val b2 = seen.filter { it.itemId == "b2" }
        assertTrue(b2.isNotEmpty())
        assertTrue("b2 with b1's author or cover version: $b2", b2.all { it.author == null && it.coverVersion == null })
        b2Meta.emit(PlayerMeta("Jemand", coverVersion = 7))
        assertEquals(7L, v.state.first { it.author == "Jemand" }.coverVersion)
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
