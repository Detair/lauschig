package io.wolftown.lauschig.ui.detail

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.Initials
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel

@Composable
fun ItemDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenItem: (itemId: String) -> Unit = {},
    onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionOnce()
    ItemDetailScreen(
        state, onBack, viewModel::onPrimary, { viewModel.download(); askNotifications() }, viewModel::pauseDownload,
        viewModel::deleteDownload, viewModel::onChapter, modifier,
        topActions = { if (state.found) ProgressMenuButton(state.itemId) },
        finished = { if (state.found) FinishedCard(state.itemId, onOpenItem, Modifier.padding(top = 16.dp)) },
        onOpenBrowse = onOpenBrowse,
    )
}

@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDeleteDownload: () -> Unit,
    onChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable () -> Unit = {},
    finished: @Composable () -> Unit = {},
    onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
) {
    val settledOffline = LocalOfflineDimming.current
    LazyColumn(modifier.fillMaxSize()) {
        item("back") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
                Spacer(Modifier.weight(1f))
                topActions()
            }
        }
        if (!state.found) {
            item("missing") {
                Text(stringResource(R.string.detail_not_found), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            return@LazyColumn
        }
        item("header") { Header(state, onPrimary, onDownload, onPause, onDeleteDownload, onOpenBrowse) }
        item("finished") { Box(Modifier.padding(horizontal = 16.dp)) { finished() } }
        item("chapters-title") {
            Text(
                stringResource(R.string.detail_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        if (state.chapters.isEmpty() && !state.detailsLoaded && settledOffline) {
            item("chapters-offline") {
                Text(
                    stringResource(R.string.detail_chapters_offline_hint),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        chapterItems(state.chapters, onChapter)
    }
}

@Composable
private fun Header(state: ItemDetailUiState, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(state)
        Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        state.subtitle?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
        Chips(state, onOpenBrowse)
        PrimaryButton(state, onPrimary)
        DownloadControls(state, onDownload, onPause, onDelete)
        state.description?.let { Description(it) }
    }
}

@Composable
private fun Cover(state: ItemDetailUiState) {
    val model = coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, state.coverVersion)
    Box(Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).testTag("cover")) {
        Initials(state.title)
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private class ChipSpec(val label: String, val target: Pair<BrowseKind, String>)

private val people = Catalog()

/** One chip per author and narrator, the series and each genre; all open the matching entity page. */
@Composable
private fun Chips(state: ItemDetailUiState, onOpenBrowse: (BrowseKind, String) -> Unit) {
    val seriesLabel = state.seriesName?.let { name ->
        state.seriesSequence?.let { stringResource(R.string.detail_series_band, name, it) } ?: name
    }
    val specs = buildList {
        people.splitPeople(state.author).forEach { add(ChipSpec(it, BrowseKind.AUTHOR to it)) }
        people.splitPeople(state.narrator).forEach { add(ChipSpec(it, BrowseKind.NARRATOR to it)) }
        state.seriesName?.let { add(ChipSpec(seriesLabel ?: it, BrowseKind.SERIES to it)) }
        state.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { add(ChipSpec(it, BrowseKind.GENRE to it)) }
    }
    if (specs.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        specs.forEach { Chip(it.label) { onOpenBrowse(it.target.first, it.target.second) } }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PrimaryButton(state: ItemDetailUiState, onPrimary: () -> Unit) {
    val label = when (val p = state.primary) {
        PrimaryAction.Play -> stringResource(R.string.detail_play)
        is PrimaryAction.Continue ->
            if (p.chapterNumber != null) stringResource(R.string.detail_continue_chapter, p.chapterNumber) else stringResource(R.string.detail_continue)
        PrimaryAction.ListenAgain -> stringResource(R.string.detail_listen_again)
    }
    val settledOffline = LocalOfflineDimming.current
    val playEnabled = state.playableOffline || !settledOffline
    Button(onClick = onPrimary, enabled = playEnabled, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(label) }
    if (!playEnabled) {
        Text(stringResource(R.string.error_not_downloaded_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun DownloadControls(state: ItemDetailUiState, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit) {
    // What the confirm dialog is for; null = hidden. Both removal flavours reuse the same dialog.
    var confirm by rememberSaveable { mutableStateOf(false) }
    // Starting, resuming and retrying need the server; removing never does.
    val canStart = !LocalOfflineDimming.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val d = state.download) {
            DownloadUi.None -> {
                OutlinedButton(onClick = onDownload, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detail_download)) }
            }
            DownloadUi.Queued -> {
                Text(stringResource(R.string.detail_download_queued), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onDelete) { Text(stringResource(R.string.detail_download_cancel)) }
            }
            is DownloadUi.Running -> {
                Text(stringResource(R.string.detail_downloading, d.percent), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.detail_download_pause)) }
                    TextButton(onClick = { if (d.hasPartialData) confirm = true else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
                }
            }
            is DownloadUi.Paused -> {
                Text(stringResource(R.string.detail_download_paused), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDownload, enabled = canStart) { Text(stringResource(R.string.detail_download_resume)) }
                    TextButton(onClick = { if (d.hasPartialData) confirm = true else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
                }
            }
            is DownloadUi.Complete -> {
                Text(
                    stringResource(R.string.detail_downloaded, Formatter.formatShortFileSize(LocalContext.current, d.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.detail_download_delete)) }
            }
            is DownloadUi.Failed -> {
                Text(stringResource(d.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDownload, enabled = canStart) { Text(stringResource(R.string.detail_download_retry)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.detail_download_remove)) }
                }
            }
        }
        if (!canStart && state.download.needsServer()) Hint(stringResource(R.string.detail_download_offline))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.detail_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.detail_delete_dismiss)) } },
        )
    }
}

private fun DownloadUi.needsServer() = this is DownloadUi.None || this is DownloadUi.Paused || this is DownloadUi.Failed

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.detail_description_less else R.string.detail_description_more))
            }
        }
    }
}
