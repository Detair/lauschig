package io.wolftown.lauschig.ui.downloads

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.DownloadEntryRow
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.download.DownloadList
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadSort
import io.wolftown.lauschig.download.DownloadWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What one download is doing, as the page shows it. */
@Immutable
sealed interface EntryStatus {
    /** [waitingForNetwork]: the worker found no connection; [retrying]: the last attempt failed and will be repeated. */
    data class Queued(val waitingForNetwork: Boolean, val retrying: Boolean) : EntryStatus
    data class Running(val percent: Int) : EntryStatus
    /** [forStream]: paused automatically so a playing stream keeps the connection (`stream_priority`). */
    data class Paused(val forStream: Boolean) : EntryStatus
    data class Failed(@StringRes val reason: Int) : EntryStatus
    data object Complete : EntryStatus
}

@Immutable
data class DownloadEntryUi(
    val key: String,
    val title: String,
    val subtitle: String?,
    val status: EntryStatus,
    val bytesOnDisk: Long,
    val bytesTotal: Long,
    val finished: Boolean,
)

@Immutable
data class DownloadsUiState(
    val loaded: Boolean = false,
    val entries: List<DownloadEntryUi> = emptyList(),
    /** Downloads before the search filter. */
    val total: Int = 0,
    val usedBytes: Long = 0,
    /** Free space of the app storage volume; null until read. */
    val freeBytes: Long? = null,
    val finishedCount: Int = 0,
    val finishedBytes: Long = 0,
    val sort: DownloadSort = DownloadSort.ADDED,
    val query: String = "",
)

/** Maps a row to its status; the reasons are the worker's machine codes (see [DownloadWorker]). */
fun entryStatus(row: DownloadEntryRow): EntryStatus = when (row.state) {
    DownloadState.QUEUED -> EntryStatus.Queued(waitingForNetwork = row.error == OFFLINE, retrying = row.error != null && row.error != OFFLINE)
    DownloadState.RUNNING -> EntryStatus.Running(if (row.bytesTotal > 0) (row.bytesDone * 100 / row.bytesTotal).toInt().coerceIn(0, 100) else 0)
    DownloadState.PAUSED -> EntryStatus.Paused(forStream = row.error == DownloadWorker.STREAM_PRIORITY)
    DownloadState.FAILED -> EntryStatus.Failed(
        when (row.error) {
            "storage_full" -> R.string.download_error_storage_full
            "http_404" -> R.string.download_error_not_found
            else -> R.string.download_error_generic
        },
    )
    DownloadState.COMPLETE -> EntryStatus.Complete
}

private const val OFFLINE = "offline"

/**
 * The download management page: every download of the signed-in server with its state and size, sorted and searched in
 * memory ([DownloadList]). Actions go through the [DownloadManager] exactly like on the detail page, so the worker rules
 * (Wi-Fi, stream priority) apply unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel(
    accountStore: AccountStore,
    private val dao: DownloadDao,
    private val downloads: DownloadManager,
    private val freeSpace: suspend () -> Long?,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(accountStore: AccountStore, dao: DownloadDao, downloads: DownloadManager, files: DownloadFiles) :
        this(accountStore, dao, downloads, { withContext(Dispatchers.IO) { runCatching { files.freeSpace() }.getOrNull() } }, Dispatchers.Default)

    private val sort = MutableStateFlow(DownloadSort.ADDED)
    private val query = MutableStateFlow("")
    private val serverId = accountStore.account.map { it?.serverId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    @Volatile private var rows: List<DownloadEntryRow> = emptyList()

    val state: StateFlow<DownloadsUiState> = serverId.flatMapLatest { sid ->
        if (sid == null) flowOf(DownloadsUiState(loaded = true))
        else combine(dao.observeEntries(sid), sort, query) { all, so, q ->
            rows = all
            val totals = DownloadList.totals(all)
            DownloadsUiState(
                loaded = true,
                entries = DownloadList.arrange(all, so, q).map { it.toUi() },
                total = all.size,
                usedBytes = totals.usedBytes,
                freeBytes = freeSpace(),
                finishedCount = totals.finishedCount,
                finishedBytes = totals.finishedBytes,
                sort = so,
                query = q,
            )
        }
    }.flowOn(dispatcher).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    private fun DownloadEntryRow.toUi() =
        DownloadEntryUi(itemKey, DownloadList.displayTitle(this), subtitle?.takeIf { it.isNotBlank() }, entryStatus(this), bytesDone, bytesTotal, isFinished)

    fun setSort(s: DownloadSort) { sort.value = s }
    fun setQuery(q: String) { query.value = q }

    /** Keeps the partial data (PAUSED). */
    fun pause(key: String) = withServer { downloads.cancel(it, key, deleteFiles = false) }
    /** Resume a paused or retry a failed download; the normal network rule applies. */
    fun resume(key: String) = withServer { downloads.enqueue(it, key) }
    /** Cancel (partial data goes) or delete a finished download; progress stays. */
    fun remove(key: String) = withServer { downloads.cancel(it, key, deleteFiles = true) }

    /** Deletes the complete downloads of every title marked as heard. */
    fun deleteFinished() = withServer { sid -> DownloadList.finishedKeys(rows).forEach { downloads.cancel(sid, it, deleteFiles = true) } }

    private fun withServer(block: suspend (String) -> Unit) {
        viewModelScope.launch { block(serverId.filterNotNull().first()) }
    }
}
