package io.wolftown.lauschig.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.CollectionWithCount
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.detail.formatClock

/** Content of the Library tabs "Collections" and "Playlists": the cached lists, newest from the server when online. */
@Composable
fun CollectionListTab(kind: CollectionKind, onOpen: (id: String) -> Unit, modifier: Modifier = Modifier, viewModel: CollectionListViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.refreshOnce() }
    val lists by remember(kind) { viewModel.lists(kind) }.collectAsStateWithLifecycle(null)
    CollectionList(kind, lists, onOpen, modifier)
}

@Composable
fun CollectionList(kind: CollectionKind, lists: List<CollectionWithCount>?, onOpen: (id: String) -> Unit, modifier: Modifier = Modifier) {
    val rows = lists ?: return
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (kind == CollectionKind.COLLECTION) R.string.collections_empty else R.string.playlists_empty),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("collectionList")) {
        items(rows, key = { it.id }) { c ->
            ListItem(
                headlineContent = { Text(c.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(pluralStringResource(R.plurals.collection_titles, c.count, c.count)) },
                leadingContent = {
                    Icon(if (kind == CollectionKind.COLLECTION) Icons.Rounded.CollectionsBookmark else Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null)
                },
                modifier = Modifier.fillMaxWidth().clickable { onOpen(c.id) }.testTag("collection:${c.id}"),
            )
        }
    }
}

@Composable
fun CollectionRoute(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline = LocalOfflineDimming.current
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    val playable: (CollectionEntryUi) -> Boolean = { e ->
        !offline || when (e) {
            is CollectionEntryUi.Book -> e.summary.isDownloaded
            is CollectionEntryUi.Episode -> e.downloaded
        }
    }
    CollectionScreen(
        state, onBack, modifier,
        onPlayAll = { viewModel.playAll(playable) },
        onOpenItem = onOpenItem,
        onPlay = viewModel::play,
        onMenu = { menuFor = it },
        canPlay = playable,
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

@Composable
fun CollectionScreen(
    state: CollectionUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayAll: () -> Unit = {},
    onOpenItem: (itemId: String) -> Unit = {},
    onPlay: (key: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
    canPlay: (CollectionEntryUi) -> Boolean = { true },
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().testTag("collectionScreen"),
    ) {
        item("back", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            }
        }
        if (state.loaded && !state.found) {
            item("missing", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.collection_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            return@LazyVerticalGrid
        }
        item("header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    pluralStringResource(R.plurals.collection_titles, state.entries.size, state.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = onPlayAll, enabled = state.entries.any(canPlay), modifier = Modifier.fillMaxWidth().testTag("playAll")) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.collection_play_all), Modifier.padding(start = 8.dp))
                }
                if (state.missing > 0) {
                    Text(stringResource(R.string.collection_missing_titles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.entries, key = { it.key }, span = { e -> if (e is CollectionEntryUi.Episode) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { e ->
            when (e) {
                is CollectionEntryUi.Book -> CoverTile(e.summary, onClick = { onOpenItem(e.summary.id) }, onLongClick = { onMenu(e.summary) }, onPlay = { onPlay(e.summary.id) })
                is CollectionEntryUi.Episode -> EpisodeEntryRow(e, enabled = canPlay(e), onPlay = { onPlay(e.key) })
            }
        }
    }
}

@Composable
private fun EpisodeEntryRow(e: CollectionEntryUi.Episode, enabled: Boolean, onPlay: () -> Unit) {
    val meta = listOfNotNull(e.podcast, e.durationSec.takeIf { it > 0 }?.let { formatClock(it) }).joinToString(" · ")
    ListItem(
        headlineContent = { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = meta.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = {
            when {
                e.finished -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.podcast_episode_heard), tint = MaterialTheme.colorScheme.primary)
                e.downloaded -> Icon(Icons.Rounded.DownloadDone, stringResource(R.string.cover_offline_available), tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            }
        },
        modifier = Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onPlay).testTag("entry:${e.key}"),
    )
}
