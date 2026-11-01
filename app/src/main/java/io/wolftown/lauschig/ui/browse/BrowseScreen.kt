package io.wolftown.lauschig.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.Initials
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.detail.Description
import io.wolftown.lauschig.ui.nav.BrowseKind

@Composable
fun BrowseRoute(
    onBack: () -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    BrowseScreen(
        state, onBack, viewModel::setKinds, onOpenItem, onOpenBrowse, modifier,
        onPlay = actions::play, onMenu = { menuFor = it },
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

/** Header (name, count, author photo/bio), kind filter and the titles; tap opens details, ▶ plays, long-press opens the menu. */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onBack: () -> Unit,
    onKinds: (Set<LibraryKind>) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (itemId: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
        KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { Header(state) }
            if (state.series.isNotEmpty()) {
                item(key = "series", span = { GridItemSpan(maxLineSpan) }, contentType = "series") {
                    Column {
                        Text(stringResource(R.string.browse_series_row), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.series.forEach { s ->
                                AssistChip(onClick = { onOpenBrowse(BrowseKind.SERIES, s.name) }, label = { Text(s.name) })
                            }
                        }
                    }
                }
            }
            if (state.loaded && state.entries.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Text(stringResource(R.string.browse_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(state.entries, key = { it.key() }, contentType = { if (it is BrowseEntry.Tile) "tile" else "missing" }) { entry ->
                when (entry) {
                    is BrowseEntry.Tile -> CoverTile(
                        entry.item,
                        onClick = { onOpenItem(entry.item.id) }, onLongClick = { onMenu(entry.item) }, onPlay = { onPlay(entry.item.id) },
                    )
                    is BrowseEntry.Missing -> MissingVolume(entry.sequence)
                }
            }
        }
    }
}

private fun BrowseEntry.key(): String = when (this) {
    is BrowseEntry.Tile -> item.key
    is BrowseEntry.Missing -> "missing:$sequence"
}

@Composable
private fun Header(state: BrowseUiState) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AuthorImage(state)
            Column {
                Text(state.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    pluralStringResource(R.plurals.browse_title_count, state.count, state.count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.author?.description?.takeIf { it.isNotBlank() }?.let { Description(it) }
    }
}

/** Only while online (the image needs the server); the initials underneath stay if loading fails. */
@Composable
private fun AuthorImage(state: BrowseUiState) {
    val author = state.author ?: return
    val base = LocalServerBaseUrl.current
    if (author.imagePath == null || base == null) return
    val url = remember(base, author.id) {
        base.newBuilder().addPathSegments("api/authors").addPathSegment(author.id).addPathSegment("image").build().toString()
    }
    Box(Modifier.size(96.dp).clip(CircleShape).testTag("authorImage")) {
        Initials(state.name)
        AsyncImage(
            model = url, contentDescription = stringResource(R.string.browse_author_image, state.name),
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Greyed, not clickable stand-in for a volume number the library lacks. */
@Composable
private fun MissingVolume(sequence: String) {
    Column(Modifier.testTag("missingVolume")) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.browse_missing_volume, sequence),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Text(
            stringResource(R.string.browse_missing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
