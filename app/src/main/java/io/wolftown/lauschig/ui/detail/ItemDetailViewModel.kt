package io.wolftown.lauschig.ui.detail

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ItemWithDetails
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.playback.TimelineMapper
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.nav.ItemDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PrimaryAction {
    data object Play : PrimaryAction
    /** [chapterNumber] is 1-based; null when the book has no chapters. */
    data class Continue(val chapterNumber: Int?) : PrimaryAction
    data object ListenAgain : PrimaryAction
}

@Immutable
sealed interface DownloadUi {
    data object None : DownloadUi
    data object Queued : DownloadUi
    data class Running(val percent: Int) : DownloadUi
    data object Paused : DownloadUi
    data class Complete(val bytes: Long) : DownloadUi
    /** [message] maps the machine code stored with the download. */
    data class Failed(@StringRes val message: Int) : DownloadUi
}

@Immutable
data class ChapterRow(
    val index: Int,
    val title: String,
    val durationSec: Double,
    val isCurrent: Boolean,
    /** Position inside the chapter, only for the current chapter. */
    val positionInChapterSec: Double? = null,
)

@Immutable
data class ItemDetailUiState(
    val found: Boolean = false,
    val itemId: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    val seriesSequence: String? = null,
    val genres: List<String> = emptyList(),
    /** Plain text (HTML stripped). */
    val description: String? = null,
    val coverPath: String? = null,
    val coverVersion: Long = 0,
    val durationSec: Double = 0.0,
    val primary: PrimaryAction = PrimaryAction.Play,
    val playEnabled: Boolean = true,
    @StringRes val playDisabledReason: Int? = null,
    val download: DownloadUi = DownloadUi.None,
    val downloadEnabled: Boolean = true,
    val detailsLoaded: Boolean = false,
    val online: Boolean = false,
    val chapters: List<ChapterRow> = emptyList(),
)

/** Detail screen data from Room; the network is only used for one background [loadDetails] when details are missing. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemDetailViewModel(
    private val itemId: String,
    private val accountStore: AccountStore,
    private val itemDao: ItemDao,
    private val downloads: DownloadManager,
    private val player: PlayerController,
    connection: Flow<ConnectionState>,
    private val loadDetails: suspend (serverId: String, itemId: String) -> Unit,
    private val covers: CoverPathCache,
) : ViewModel() {

    @Inject constructor(
        savedState: SavedStateHandle,
        accountStore: AccountStore,
        itemDao: ItemDao,
        downloads: DownloadManager,
        player: PlayerController,
        api: ApiProvider,
        sync: LibrarySync,
        covers: CoverPathCache,
    ) : this(savedState.toRoute<ItemDetail>().itemId, accountStore, itemDao, downloads, player, api.connection, sync::loadDetails, covers)

    private enum class Load { IDLE, RUNNING, DONE, FAILED }

    @Volatile private var serverId: String? = null
    @Volatile private var chapterStarts: List<Double> = emptyList()
    private var load = Load.IDLE

    private val online: Flow<Boolean> = connection.map { it is ConnectionState.Online }.distinctUntilChanged()
    private val account = accountStore.account.map { it?.serverId }.distinctUntilChanged()

    init {
        // One attempt per screen instance; only a failed attempt is retried, and only when the inputs change (e.g. offline -> online).
        viewModelScope.launch {
            account.flatMapLatest { sid ->
                if (sid == null) flowOf(Triple(null, false, false))
                else combine(online, itemDao.observeItem(sid, itemId).map { it?.item?.detailsLoaded }.distinctUntilChanged()) { on, loaded ->
                    Triple(sid, on, loaded == false)
                }
            }.distinctUntilChanged().collect { (sid, on, missing) ->
                if (sid != null && on && missing && (load == Load.IDLE || load == Load.FAILED)) {
                    load = Load.RUNNING
                    launch {
                        load = try {
                            loadDetails(sid, itemId); Load.DONE
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            Load.FAILED
                        }
                    }
                }
            }
        }
    }

    private val playerSlice: Flow<PlayerSlice> = player.state.map { it.slice(itemId) }.distinctUntilChanged()

    val state: StateFlow<ItemDetailUiState> = account
        .flatMapLatest { sid ->
            serverId = sid
            if (sid == null) flowOf(ItemDetailUiState())
            else combine(itemDao.observeItem(sid, itemId), downloads.observe(sid, itemId), playerSlice, online) { d, dl, p, on ->
                if (d == null) ItemDetailUiState(itemId = itemId, online = on) else build(d, dl, p, on)
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItemDetailUiState(itemId = itemId))

    private fun build(d: ItemWithDetails, dl: DownloadProgress?, p: PlayerSlice, online: Boolean): ItemDetailUiState {
        val item = d.item
        val progress = d.progress
        val downloaded = dl?.state == DownloadState.COMPLETE
        val mapper = TimelineMapper(emptyList())
        chapterStarts = d.chapters.map { it.startSec }

        val savedChapter = progress?.let { mapper.chapterAt(it.currentTimeSec, d.chapters) } ?: -1
        val primary = when {
            progress == null -> PrimaryAction.Play
            progress.isFinished -> PrimaryAction.ListenAgain
            progress.currentTimeSec <= 0.0 -> PrimaryAction.Play
            else -> PrimaryAction.Continue(if (savedChapter >= 0) savedChapter + 1 else null)
        }
        val playable = downloaded || online || p.isThis
        val currentIndex: Int
        val currentPos: Double
        if (p.isThis) {
            currentIndex = p.chapterIndex
            currentPos = p.positionSec
        } else if (progress != null) {
            currentIndex = savedChapter
            currentPos = progress.currentTimeSec
        } else {
            currentIndex = -1
            currentPos = 0.0
        }
        val rows = d.chapters.mapIndexed { i, c ->
            val cur = i == currentIndex
            ChapterRow(i, c.title, (c.endSec - c.startSec).coerceAtLeast(0.0), cur,
                if (cur) (currentPos - c.startSec).coerceIn(0.0, (c.endSec - c.startSec).coerceAtLeast(0.0)) else null)
        }
        return ItemDetailUiState(
            found = true,
            itemId = itemId,
            title = item.title,
            subtitle = item.subtitle?.takeIf { it.isNotBlank() },
            author = item.author?.takeIf { it.isNotBlank() },
            narrator = item.narrator?.takeIf { it.isNotBlank() },
            seriesName = item.seriesName?.takeIf { it.isNotBlank() },
            seriesSequence = item.seriesSequence?.takeIf { it.isNotBlank() },
            genres = item.genres,
            description = item.description?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }?.takeIf { it.isNotEmpty() },
            coverPath = if (downloaded) covers.pathFor(item.serverId, item.id) else null,
            coverVersion = item.coverUpdatedAt,
            durationSec = item.durationSec,
            primary = primary,
            playEnabled = playable,
            playDisabledReason = if (playable) null else R.string.error_not_downloaded_offline,
            download = dl.toUi(d),
            downloadEnabled = online || dl != null,
            detailsLoaded = item.detailsLoaded,
            online = online,
            chapters = rows,
        )
    }

    private fun DownloadProgress?.toUi(d: ItemWithDetails): DownloadUi = when (this?.state) {
        null -> DownloadUi.None
        DownloadState.QUEUED -> DownloadUi.Queued
        DownloadState.RUNNING -> DownloadUi.Running(if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else 0)
        DownloadState.PAUSED -> DownloadUi.Paused
        DownloadState.COMPLETE -> DownloadUi.Complete(bytesTotal)
        DownloadState.FAILED -> DownloadUi.Failed(errorMessage(d.download?.error))
    }

    @StringRes
    private fun errorMessage(code: String?): Int = when {
        code == "storage_full" -> R.string.download_error_storage_full
        code == "http_404" -> R.string.download_error_not_found
        else -> R.string.download_error_generic
    }

    /** Hauptknopf: "Listen again" restarts at 0, otherwise the saved progress is used. */
    fun onPrimary() {
        val sid = serverId ?: return
        when (state.value.primary) {
            PrimaryAction.ListenAgain -> player.play(sid, itemId, 0.0)
            else -> player.play(sid, itemId)
        }
    }

    fun onChapter(index: Int) {
        val sid = serverId ?: return
        if (player.state.value.itemId == itemId) {
            player.seekToChapter(index)
        } else {
            val start = chapterStarts.getOrNull(index) ?: return
            player.play(sid, itemId, start)
        }
    }

    fun download() = launchDownload { sid -> downloads.enqueue(sid, itemId) }
    /** Keeps the partial data (state becomes PAUSED). */
    fun pauseDownload() = launchDownload { sid -> downloads.cancel(sid, itemId, deleteFiles = false) }
    /** Cancels a running/paused download or deletes a finished one, including files. */
    fun deleteDownload() = launchDownload { sid -> downloads.cancel(sid, itemId, deleteFiles = true) }

    private fun launchDownload(block: suspend (String) -> Unit) {
        val sid = serverId ?: return
        viewModelScope.launch { block(sid) }
    }
}

private data class PlayerSlice(val isThis: Boolean, val chapterIndex: Int, val positionSec: Double)

private fun PlayerUiState.slice(itemId: String) =
    if (this.itemId == itemId) PlayerSlice(true, chapterIndex, kotlin.math.floor(positionSec)) else PlayerSlice(false, -1, 0.0)
