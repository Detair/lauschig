package io.wolftown.lauschig.ui.player

import kotlinx.coroutines.flow.onStart
import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.StreamLoad
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.settings.RemainingTime
import io.wolftown.lauschig.settings.UiSettings
import kotlinx.coroutines.launch
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

/**
 * Who and which series a loaded title belongs to (for an episode: its podcast as [author]), and its cover's server
 * version (`coverUpdatedAt`), which the player's cover URL carries like the tiles' do.
 */
data class PlayerMeta(val author: String?, val seriesName: String? = null, val seriesSequence: String? = null, val coverVersion: Long = 0)

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
    /** Book time left in the current chapter (whole book without chapters) and in the whole book. */
    val chapterRemainingSec: Double = 0.0,
    val bookRemainingSec: Double = 0.0,
    /** What the right timestamp counts down (a setting; a tap toggles it). */
    val remainingTime: RemainingTime = RemainingTime.CHAPTER,
    val speed: Float = 1f,
    val isStreaming: Boolean = false,
    val isLoading: Boolean = false,
    val streamLoad: StreamLoad? = null,
    val downloadThrottled: Boolean = false,
    val chapters: List<ChapterRow> = emptyList(),
    val error: Int? = null,
    /** The quieter line under the title (see [PlayerMeta]); null while unknown. */
    val author: String? = null,
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    /** The cover's server version (see [PlayerMeta]); null until this title's row is read (no server cover until then). */
    val coverVersion: Long? = null,
    /** The seek steps of the settings, for the back / forward buttons. */
    val seekBackSec: Int = PlaybackSettings.DEFAULT_SEEK_BACK,
    val seekForwardSec: Int = PlaybackSettings.DEFAULT_SEEK_FORWARD,
)

/** Maps the controller state to the player screen and exposes the player actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel(
    private val player: PlayerController,
    metaOf: (itemId: String) -> Flow<PlayerMeta?> = { flowOf(null) },
    remainingTime: Flow<RemainingTime> = flowOf(RemainingTime.CHAPTER),
    private val setRemainingTime: suspend (RemainingTime) -> Unit = {},
    chaptersOf: (itemId: String) -> Flow<List<ChapterInfo>>,
) : ViewModel() {

    @Inject constructor(player: PlayerController, accountStore: AccountStore, itemDao: ItemDao, ui: UiSettings) :
        this(
            player,
            metaOf = { itemId ->
                val key = PlayableKey.parse(itemId)
                accountStore.account.map { DeviceLibrary.serverFor(it?.serverId, itemId) }.distinctUntilChanged().flatMapLatest { sid ->
                    // An episode's row is its podcast's: the podcast is the quieter line.
                    if (sid == null) flowOf(null)
                    else itemDao.observeItemRow(sid, key.libraryItemId).map { row ->
                        when {
                            row == null -> PlayerMeta(null)
                            key.episodeId != null -> PlayerMeta(row.title, coverVersion = row.coverUpdatedAt)
                            else -> PlayerMeta(row.author, row.seriesName, row.seriesSequence, row.coverUpdatedAt)
                        }
                    }
                }
            },
            chaptersOf = { itemId ->
                accountStore.account.map { DeviceLibrary.serverFor(it?.serverId, itemId) }.distinctUntilChanged().flatMapLatest { sid ->
                    if (sid == null) flowOf(emptyList())
                    else itemDao.observeChapters(sid, itemId).map { cs -> cs.map { ChapterInfo(it.title, it.startSec, it.endSec) } } // chapters live under the playable key
                }
            },
            remainingTime = ui.playerRemainingTime,
            setRemainingTime = ui::setPlayerRemainingTime,
        )

    private val loadedId = player.state.map { it.itemId }.distinctUntilChanged()

    private val chapters: Flow<List<ChapterInfo>> =
        loadedId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chaptersOf(id) }

    /**
     * The loaded title's meta, tagged with the title it belongs to: on a change of title it is "none" until the new
     * title's row is read, so the new title is never shown with the previous one's author or cover version.
     */
    private val meta: Flow<Pair<String?, PlayerMeta?>> = loadedId.flatMapLatest { id ->
        if (id == null) flowOf(null to null) else metaOf(id).map { id to it }.onStart { emit(id to null) }
    }.distinctUntilChanged()

    val state: StateFlow<PlayerScreenState> = combine(player.state, chapters, meta, remainingTime) { p, chs, (metaFor, meta), remaining ->
        val m = meta.takeIf { metaFor == p.itemId }
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
            chapterRemainingSec = length - inChapter,
            bookRemainingSec = (p.durationSec - p.positionSec).coerceAtLeast(0.0),
            remainingTime = remaining,
            speed = p.speed,
            isStreaming = p.isStreaming,
            isLoading = p.isLoading,
            streamLoad = p.streamLoad,
            downloadThrottled = p.downloadThrottled,
            chapters = chs.mapIndexed { i, c ->
                val len = (c.endSec - c.startSec).coerceAtLeast(0.0)
                val cur = i == p.chapterIndex
                ChapterRow(i, c.title, len, cur, if (cur) inChapter.coerceAtMost(len) else null)
            },
            error = p.error,
            author = m?.author,
            seriesName = m?.seriesName,
            seriesSequence = m?.seriesSequence,
            coverVersion = m?.coverVersion,
            seekBackSec = p.seekBackSec,
            seekForwardSec = p.seekForwardSec,
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

    /** The right timestamp switches between the chapter's and the book's time left; the choice is kept. */
    fun toggleRemainingTime() {
        val next = if (state.value.remainingTime == RemainingTime.CHAPTER) RemainingTime.BOOK else RemainingTime.CHAPTER
        viewModelScope.launch { setRemainingTime(next) }
    }

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
