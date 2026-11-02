package io.wolftown.lauschig.ui.common

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.detail.rememberNotificationPermissionOnce

/** "⋮" menu with the progress actions of [itemId] (detail screen, player); [onEnqueue] adds "Play next" on top. */
@Composable
fun ProgressMenuButton(itemId: String, modifier: Modifier = Modifier, viewModel: ItemActionsViewModel = hiltViewModel(), onEnqueue: (() -> Unit)? = null) {
    val download by remember(itemId) { viewModel.downloadState(itemId) }.collectAsStateWithLifecycle(QuickDownload.None)
    val progress by remember(itemId) { viewModel.progressState(itemId) }.collectAsStateWithLifecycle(null)
    val host = rememberProgressActionHost(itemId, viewModel, download)
    var open by remember { mutableStateOf(false) }
    val offline = LocalOfflineDimming.current
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("progressMenu")) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.progress_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onEnqueue?.let { enqueue ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_add)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = { open = false; enqueue() },
                    modifier = Modifier.testTag("enqueue"),
                )
            }
            progressEntries(progress, offline, local = DeviceLibrary.isLocalKey(itemId)).forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label(progress))) },
                    leadingIcon = { Icon(entry.icon, contentDescription = null) },
                    onClick = { open = false; host.run(entry) },
                )
            }
        }
    }
    host.Dialogs()
}

/**
 * Shown once a title is finished: offers the next volume of the series (play, download, details) and deleting
 * the title's own download. Renders nothing for an unfinished title.
 */
@Composable
fun FinishedCard(
    itemId: String,
    onOpenDetails: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemActionsViewModel = hiltViewModel(),
) {
    val progress by remember(itemId) { viewModel.progressState(itemId) }.collectAsStateWithLifecycle(null)
    if (progress?.isFinished != true) return
    val next by remember(itemId) { viewModel.nextVolume(itemId) }.collectAsStateWithLifecycle(null)
    val ownDownload by remember(itemId) { viewModel.downloadState(itemId) }.collectAsStateWithLifecycle(QuickDownload.None)
    val nextId = next?.id
    val nextDownload by remember(nextId) { viewModel.downloadState(nextId) }.collectAsStateWithLifecycle(QuickDownload.None)
    val askNotifications = rememberNotificationPermissionOnce()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val offline = LocalOfflineDimming.current
    Card(modifier.fillMaxWidth().testTag("finishedCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.finished_title), style = MaterialTheme.typography.titleMedium)
            next?.let { n ->
                val label = n.seriesSequence?.let { stringResource(R.string.finished_next_volume, it, n.title) } ?: stringResource(R.string.finished_next, n.title)
                Text(label, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val playable = nextDownload == QuickDownload.Complete || !offline
                    FilledTonalButton(onClick = { viewModel.play(n.id) }, enabled = playable, modifier = Modifier.testTag("playNext")) {
                        Text(stringResource(R.string.player_play))
                    }
                    if (nextDownload == QuickDownload.None && !offline) {
                        OutlinedButton(onClick = { viewModel.download(n.id); askNotifications() }, modifier = Modifier.testTag("downloadNext")) {
                            Text(stringResource(R.string.detail_download))
                        }
                    }
                    TextButton(onClick = { onOpenDetails(n.id) }) { Text(stringResource(R.string.actions_details)) }
                }
            }
            if (ownDownload == QuickDownload.Complete) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("deleteFinished")) {
                    Text(stringResource(R.string.finished_delete_download))
                }
            }
        }
    }
    if (confirmDelete) {
        DeleteDownloadDialog(onConfirm = { confirmDelete = false; viewModel.deleteDownload(itemId) }, onDismiss = { confirmDelete = false })
    }
}
