package io.wolftown.lauschig.ui.player

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.detail.formatClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt

/** Minimal chapter data the player needs; [startSec]/[endSec] are book-wide. */
data class ChapterInfo(val title: String, val startSec: Double, val endSec: Double)

@Immutable
data class PlayerScreenState(
    val itemId: String? = null,
    val title: String = "",
    val coverPath: String? = null,
    val isPlaying: Boolean = false,
    val chapterTitle: String? = null,
    val chapterStartSec: Double = 0.0,
    /** 0..1 within the current chapter (whole book if there are no chapters). */
    val chapterProgress: Float = 0f,
    val chapterPositionSec: Double = 0.0,
    val chapterDurationSec: Double = 0.0,
    val chapterElapsedText: String = "00:00",
    val chapterTotalText: String = "00:00",
    val bookElapsedText: String = "00:00",
    val bookTotalText: String = "00:00",
    val speed: Float = 1f,
    val isStreaming: Boolean = false,
    val isLoading: Boolean = false,
    val chapters: List<ChapterRow> = emptyList(),
    val error: Int? = null,
)

/** Maps the controller state to the player screen and exposes the player actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel(
    private val player: PlayerController,
    chaptersOf: (itemId: String) -> Flow<List<ChapterInfo>>,
) : ViewModel() {

    @Inject constructor(player: PlayerController, accountStore: AccountStore, itemDao: ItemDao) :
        this(player, { itemId ->
            accountStore.account.map { it?.serverId }.distinctUntilChanged().flatMapLatest { sid ->
                if (sid == null) flowOf(emptyList())
                else itemDao.observeItem(sid, itemId).map { d -> d?.chapters.orEmpty().map { ChapterInfo(it.title, it.startSec, it.endSec) } }
            }
        })

    private val chapters: Flow<List<ChapterInfo>> =
        player.state.map { it.itemId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chaptersOf(id) }

    val state: StateFlow<PlayerScreenState> = combine(player.state, chapters) { p, chs ->
        // Without chapter data the whole book acts as one chapter.
        val start = if (p.chapterIndex >= 0) p.chapterStartSec else 0.0
        val end = if (p.chapterIndex >= 0 && p.chapterEndSec > start) p.chapterEndSec else p.durationSec
        val length = (end - start).coerceAtLeast(0.0)
        val inChapter = (p.positionSec - start).coerceIn(0.0, length)
        PlayerScreenState(
            itemId = p.itemId,
            title = p.title,
            coverPath = p.coverPath,
            isPlaying = p.isPlaying,
            chapterTitle = p.chapterTitle,
            chapterStartSec = start,
            chapterProgress = if (length > 0) (inChapter / length).toFloat() else 0f,
            chapterPositionSec = inChapter,
            chapterDurationSec = length,
            chapterElapsedText = formatClock(inChapter),
            chapterTotalText = formatClock(length),
            bookElapsedText = formatClock(p.positionSec),
            bookTotalText = formatClock(p.durationSec),
            speed = p.speed,
            isStreaming = p.isStreaming,
            isLoading = p.isLoading,
            chapters = chs.mapIndexed { i, c ->
                val len = (c.endSec - c.startSec).coerceAtLeast(0.0)
                val cur = i == p.chapterIndex
                ChapterRow(i, c.title, len, cur, if (cur) inChapter.coerceAtMost(len) else null)
            },
            error = p.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerScreenState())

    fun togglePlayPause() = player.togglePlayPause()
    fun seekBy(deltaSec: Int) = player.seekBy(deltaSec)

    /** Seek to [fraction] (0..1) of the current chapter as an absolute position; 1.0 lands 1 s before the chapter end. */
    fun seekInChapter(fraction: Float) {
        val s = state.value
        val offset = (fraction.coerceIn(0f, 1f) * s.chapterDurationSec).coerceAtMost((s.chapterDurationSec - 1.0).coerceAtLeast(0.0))
        player.seekTo(s.chapterStartSec + offset)
    }

    /** More than [RESTART_THRESHOLD_SEC] into the chapter restarts it, else jumps to the previous one. */
    fun previousChapter() {
        val s = state.value
        val cur = s.chapters.indexOfFirst { it.isCurrent }
        if (cur < 0) return
        player.seekToChapter(if (s.chapterPositionSec > RESTART_THRESHOLD_SEC) cur else (cur - 1).coerceAtLeast(0))
    }

    fun nextChapter() {
        val s = state.value
        val cur = s.chapters.indexOfFirst { it.isCurrent }
        if (cur >= 0 && cur + 1 < s.chapters.size) player.seekToChapter(cur + 1)
    }

    fun seekToChapter(index: Int) = player.seekToChapter(index)

    /** Snaps to the 0.05 grid and clamps to 0.5..3.0. */
    fun setSpeed(speed: Float) = player.setSpeed(snapSpeed(speed))

    companion object {
        const val RESTART_THRESHOLD_SEC = 3.0
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        val PRESETS = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        fun snapSpeed(v: Float): Float = ((v * 20).roundToInt() / 20f).coerceIn(MIN_SPEED, MAX_SPEED)
    }
}
