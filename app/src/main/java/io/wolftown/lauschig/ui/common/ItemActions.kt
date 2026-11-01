package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.download.DownloadProgress
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.ui.detail.rememberNotificationPermissionOnce
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Download state as shown on quick actions (sheet, player); the detail screen keeps its richer [io.wolftown.lauschig.ui.detail.DownloadUi]. */
@Immutable
sealed interface QuickDownload {
    data object None : QuickDownload
    /** [percent] is null while queued. */
    data class Active(val percent: Int?) : QuickDownload
    data object Paused : QuickDownload
    data object Complete : QuickDownload
    data object Failed : QuickDownload
}

fun DownloadProgress?.toQuick(): QuickDownload = when (this?.state) {
    null -> QuickDownload.None
    DownloadState.QUEUED -> QuickDownload.Active(null)
    DownloadState.RUNNING -> QuickDownload.Active(if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).toInt().coerceIn(0, 100) else 0)
    DownloadState.PAUSED -> QuickDownload.Paused
    DownloadState.COMPLETE -> QuickDownload.Complete
    DownloadState.FAILED -> QuickDownload.Failed
}

/** Play/download actions for a title outside the detail screen (cover long-press menu, player). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItemActionsViewModel @Inject constructor(
    accountStore: AccountStore,
    private val downloads: DownloadManager,
    private val player: PlayerController,
) : ViewModel() {
    private val serverId: StateFlow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun downloadState(itemId: String?): Flow<QuickDownload> =
        if (itemId == null) flowOf(QuickDownload.None)
        else serverId.flatMapLatest { sid -> if (sid == null) flowOf(null) else downloads.observe(sid, itemId) }.map { it.toQuick() }

    fun play(itemId: String) = withServer { player.play(it, itemId) }
    fun download(itemId: String) = withServer { downloads.enqueue(it, itemId) }
    /** Cancels a running download or deletes a finished one, including files; progress is kept. */
    fun deleteDownload(itemId: String) = withServer { downloads.cancel(it, itemId, deleteFiles = true) }

    private fun withServer(block: suspend (String) -> Unit) {
        viewModelScope.launch { block(serverId.filterNotNull().first()) }
    }
}

/** Long-press menu of a cover: details, play, download / remove download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(
    item: ItemSummary,
    onDismiss: () -> Unit,
    onOpenDetails: (itemId: String) -> Unit,
    viewModel: ItemActionsViewModel = hiltViewModel(),
) {
    val download by remember(item.id) { viewModel.downloadState(item.id) }.collectAsStateWithLifecycle(QuickDownload.None)
    val askNotifications = rememberNotificationPermissionOnce()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val offline = LocalOfflineDimming.current
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("itemActions")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ActionRow(Icons.Rounded.Info, stringResource(R.string.actions_details)) { onDismiss(); onOpenDetails(item.id) }
            if (item.isDownloaded || !offline) {
                ActionRow(Icons.Rounded.PlayArrow, stringResource(R.string.player_play)) { onDismiss(); viewModel.play(item.id) }
            }
            when (val d = download) {
                QuickDownload.None, QuickDownload.Paused, QuickDownload.Failed -> if (!offline) {
                    val label = if (d == QuickDownload.None) R.string.detail_download else R.string.actions_download_resume
                    ActionRow(Icons.Rounded.Download, stringResource(label)) { viewModel.download(item.id); askNotifications(); onDismiss() }
                }
                is QuickDownload.Active -> ActionRow(Icons.Rounded.Delete, stringResource(R.string.detail_download_cancel)) {
                    viewModel.deleteDownload(item.id); onDismiss()
                }
                QuickDownload.Complete -> ActionRow(Icons.Rounded.Delete, stringResource(R.string.detail_download_delete)) { confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        DeleteDownloadDialog(onConfirm = { confirmDelete = false; viewModel.deleteDownload(item.id); onDismiss() }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
fun DeleteDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_delete_title)) },
        text = { Text(stringResource(R.string.detail_delete_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.detail_delete_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_delete_dismiss)) } },
    )
}

/**
 * Download button showing the state: start (online only), progress (tap cancels), done (tap asks to delete),
 * paused/failed (tap resumes).
 */
@Composable
fun DownloadIconButton(state: QuickDownload, onDownload: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val offline = LocalOfflineDimming.current
    when (state) {
        QuickDownload.None, QuickDownload.Paused -> IconButton(onClick = onDownload, enabled = !offline, modifier = modifier.testTag("downloadButton")) {
            Icon(Icons.Rounded.Download, stringResource(if (state == QuickDownload.None) R.string.detail_download else R.string.detail_download_resume))
        }
        QuickDownload.Failed -> IconButton(onClick = onDownload, enabled = !offline, modifier = modifier.testTag("downloadButton")) {
            Icon(Icons.Rounded.ErrorOutline, stringResource(R.string.detail_download_retry), tint = MaterialTheme.colorScheme.error)
        }
        is QuickDownload.Active -> IconButton(onClick = onDelete, modifier = modifier.testTag("downloadButton")) {
            val description = state.percent?.let { stringResource(R.string.detail_downloading, it) } ?: stringResource(R.string.detail_download_queued)
            if (state.percent == null) {
                Icon(Icons.Rounded.Downloading, description)
            } else {
                CircularProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.size(24.dp))
                Icon(Icons.Rounded.Downloading, description, Modifier.size(14.dp))
            }
        }
        QuickDownload.Complete -> IconButton(onClick = { confirmDelete = true }, modifier = modifier.testTag("downloadButton")) {
            Icon(Icons.Rounded.DownloadDone, stringResource(R.string.cover_offline_available), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (confirmDelete) DeleteDownloadDialog(onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
}
