package io.wolftown.lauschig.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.download.DownloadSort
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.StatePage

@Composable
fun DownloadsRoute(onBack: () -> Unit, onOpenItem: (key: String) -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadsScreen(
        state, onBack, onOpenItem,
        DownloadsActions(viewModel::setSort, viewModel::setQuery, viewModel::pause, viewModel::resume, viewModel::remove, viewModel::deleteFinished),
        keptSection = { KeptDownloadsSection() },
    )
}

/** Callbacks of the page; all keyed by the playable key. */
class DownloadsActions(
    val onSort: (DownloadSort) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onPause: (String) -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onDeleteFinished: () -> Unit = {},
)

/** The device's file-size formatter joins number and unit with a plain space for some sizes and a no-break one for others. */
internal fun String.noBreakSpaces(): String = replace(' ', '\u00A0')

/**
 * What the downloads occupy and what is free; when earlier accounts kept data on the device, the used figure includes it
 * and names that part. Every size in this line is joined to its unit with a no-break space, so a wrapped line never
 * separates "950 MB" into two.
 */
@Composable
private fun storageText(state: DownloadsUiState): String {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes).noBreakSpaces()
    val used = size(state.usedBytes)
    val free = state.freeBytes?.let(::size)
    return when {
        state.keptBytes > 0 && free != null -> stringResource(R.string.downloads_storage_kept, used, size(state.keptBytes), free)
        state.keptBytes > 0 -> stringResource(R.string.downloads_storage_used_kept, used, size(state.keptBytes))
        free != null -> stringResource(R.string.downloads_storage, used, free)
        else -> stringResource(R.string.downloads_storage_used, used)
    }
}

/** Storage on top, then search, sort chips, "delete finished" and one row per download with its actions. */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onOpenItem: (key: String) -> Unit,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
    /** Slot for the data of earlier accounts (needs Hilt). */
    keptSection: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmFinished by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().testTag("downloadsScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            storageText(state),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("downloadsStorage"),
        )
        if (state.otherProfilesBytes > 0) {
            Text(
                stringResource(R.string.downloads_other_profiles, size(state.otherProfilesBytes).noBreakSpaces()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("downloadsOtherProfiles"),
            )
        }
        if (state.cardMissing) {
            Text(
                stringResource(R.string.settings_storage_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("downloadsCardMissing"),
            )
        }
        keptSection()
        if (state.total > 0) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { actions.onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) }
                },
                placeholder = { Text(stringResource(R.string.downloads_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("downloadsSearch"),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadSort.entries.forEach { s ->
                    FilterChip(
                        selected = s == state.sort,
                        onClick = { actions.onSort(s) },
                        label = { Text(stringResource(s.label())) },
                        modifier = Modifier.testTag("sort:$s"),
                    )
                }
            }
        }
        if (state.finishedCount > 0) {
            OutlinedButton(
                onClick = { confirmFinished = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("deleteFinished"),
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Text(stringResource(R.string.downloads_delete_finished, state.finishedCount, size(state.finishedBytes)), Modifier.padding(start = 8.dp))
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        when {
            !state.loaded -> StatePage { LoadingState(verticalPadding = it) }
            state.total == 0 -> Empty(Icons.Rounded.DownloadForOffline, stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_hint))
            state.entries.isEmpty() -> Empty(Icons.Rounded.SearchOff, stringResource(R.string.downloads_no_match))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.key }) { e ->
                    EntryRow(e, ::size, onOpen = { onOpenItem(e.key) }, actions, onDelete = { confirmDelete = e.key })
                }
            }
        }
    }
    confirmDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; actions.onRemove(key) }, modifier = Modifier.testTag("confirmDelete")) { Text(stringResource(R.string.detail_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.detail_delete_dismiss)) } },
        )
    }
    if (confirmFinished) {
        AlertDialog(
            onDismissRequest = { confirmFinished = false },
            title = { Text(stringResource(R.string.downloads_delete_finished_title)) },
            text = { Text(pluralStringResource(R.plurals.downloads_delete_finished_message, state.finishedCount, state.finishedCount, size(state.finishedBytes))) },
            confirmButton = { TextButton(onClick = { confirmFinished = false; actions.onDeleteFinished() }, modifier = Modifier.testTag("confirmDeleteFinished")) { Text(stringResource(R.string.detail_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmFinished = false }) { Text(stringResource(R.string.detail_delete_dismiss)) } },
        )
    }
}

private fun DownloadSort.label(): Int = when (this) {
    DownloadSort.NAME -> R.string.downloads_sort_name
    DownloadSort.SIZE -> R.string.downloads_sort_size
    DownloadSort.ADDED -> R.string.downloads_sort_added
    DownloadSort.PLAYED -> R.string.downloads_sort_played
}

@Composable
private fun Empty(icon: ImageVector, headline: String, supporting: String? = null) {
    StatePage { EmptyState(icon, headline, supporting = supporting, verticalPadding = it) }
}

@Composable
private fun statusText(e: DownloadEntryUi, size: (Long) -> String): String = when (val s = e.status) {
    is EntryStatus.Queued -> stringResource(
        when {
            s.waitingForNetwork -> R.string.downloads_status_waiting_network
            s.retrying -> R.string.downloads_status_retrying
            else -> R.string.detail_download_queued
        },
    )
    is EntryStatus.Running -> stringResource(R.string.downloads_status_running, s.percent, size(e.bytesOnDisk), size(e.bytesTotal))
    is EntryStatus.Paused -> stringResource(if (s.forStream) R.string.downloads_status_paused_stream else R.string.detail_download_paused) +
        if (e.bytesOnDisk > 0) " · " + stringResource(R.string.downloads_status_partial, size(e.bytesOnDisk), size(e.bytesTotal)) else ""
    is EntryStatus.Failed -> stringResource(s.reason)
    EntryStatus.Complete -> stringResource(R.string.downloads_status_complete, size(e.bytesOnDisk)) +
        if (e.finished) " · " + stringResource(R.string.downloads_finished_badge) else ""
}

@Composable
private fun EntryRow(e: DownloadEntryUi, size: (Long) -> String, onOpen: () -> Unit, actions: DownloadsActions, onDelete: () -> Unit) {
    val failed = e.status is EntryStatus.Failed
    ListItem(
        headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                e.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    statusText(e, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val s = e.status) {
                    is EntryStatus.Running -> LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    is EntryStatus.Queued -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    else -> Unit
                }
            }
        },
        trailingContent = {
            Row {
                when (e.status) {
                    is EntryStatus.Queued, is EntryStatus.Running -> {
                        IconButton(onClick = { actions.onPause(e.key) }, modifier = Modifier.testTag("pause:${e.key}")) {
                            Icon(Icons.Rounded.Pause, stringResource(R.string.detail_download_pause))
                        }
                        IconButton(onClick = { actions.onRemove(e.key) }, modifier = Modifier.testTag("cancel:${e.key}")) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.detail_download_cancel))
                        }
                    }
                    is EntryStatus.Paused -> {
                        IconButton(onClick = { actions.onResume(e.key) }, modifier = Modifier.testTag("resume:${e.key}")) {
                            Icon(Icons.Rounded.PlayArrow, stringResource(R.string.detail_download_resume))
                        }
                        IconButton(onClick = { actions.onRemove(e.key) }, modifier = Modifier.testTag("cancel:${e.key}")) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.detail_download_cancel))
                        }
                    }
                    is EntryStatus.Failed -> {
                        IconButton(onClick = { actions.onResume(e.key) }, modifier = Modifier.testTag("retry:${e.key}")) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.detail_download_retry))
                        }
                        IconButton(onClick = { actions.onRemove(e.key) }, modifier = Modifier.testTag("cancel:${e.key}")) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.detail_download_remove))
                        }
                    }
                    EntryStatus.Complete -> IconButton(onClick = onDelete, modifier = Modifier.testTag("delete:${e.key}")) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.detail_download_delete))
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("downloadEntry:${e.key}"),
    )
}
