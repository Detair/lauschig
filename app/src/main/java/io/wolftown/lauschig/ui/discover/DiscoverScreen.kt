package io.wolftown.lauschig.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.nav.BrowseKind

@Composable
fun DiscoverRoute(
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    DiscoverScreen(
        state, viewModel::setKinds, viewModel::setSort, onOpenItem, onOpenBrowse,
        modifier = modifier, onPlay = actions::play, onMenu = { menuFor = it },
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

/** Kind filter, then the rows (recent, series, genres, tags, authors) and the sortable grid of all titles. */
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    onKinds: (Set<LibraryKind>) -> Unit,
    onSort: (DiscoverSort) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (itemId: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        full("kinds") { KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(horizontal = 0.dp)) }
        if (state.recent.isEmpty() && state.series.isEmpty() && state.genres.isEmpty() && state.tags.isEmpty() &&
            state.authors.isEmpty() && state.all.isEmpty()
        ) {
            full("empty") {
                Text(
                    stringResource(R.string.discover_empty), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp).testTag("discoverEmpty"),
                )
            }
        }
        if (state.recent.isNotEmpty()) {
            header("recentHeader", R.string.discover_recent)
            full("recent") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recent, key = { it.key }) { item ->
                        CoverTile(
                            item, Modifier.width(TILE_WIDTH), onClick = { onOpenItem(item.id) },
                            onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) },
                        )
                    }
                }
            }
        }
        if (state.series.isNotEmpty()) {
            header("seriesHeader", R.string.discover_series)
            full("series") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.series, key = { it.name }) { SeriesTileView(it) { onOpenBrowse(BrowseKind.SERIES, it.name) } }
                }
            }
        }
        if (state.genres.isNotEmpty()) {
            header("genresHeader", R.string.discover_genres)
            full("genres") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.genres.forEach { g -> AssistChip(onClick = { onOpenBrowse(BrowseKind.GENRE, g.name) }, label = { Text(g.name) }) }
                }
            }
        }
        if (state.tags.isNotEmpty()) {
            header("tagsHeader", R.string.discover_tags)
            full("tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.tags.forEach { t -> AssistChip(onClick = { onOpenBrowse(BrowseKind.TAG, t.name) }, label = { Text(t.name) }) }
                }
            }
        }
        if (state.authors.isNotEmpty()) {
            header("authorsHeader", R.string.discover_authors)
            full("authors") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.authors, key = { it.name }) { AuthorTileView(it) { onOpenBrowse(BrowseKind.AUTHOR, it.name) } }
                }
            }
        }
        if (state.all.isNotEmpty()) {
            full("allHeader") { AllTitlesHeader(state.sort, onSort) }
            items(state.all, key = { it.key }, contentType = { "tile" }) { item ->
                CoverTile(item, onClick = { onOpenItem(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) })
            }
        }
    }
}

private val TILE_WIDTH = 120.dp

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.header(key: String, title: Int) = full(key) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun DiscoverSort.label() = when (this) {
    DiscoverSort.TITLE -> R.string.discover_sort_title
    DiscoverSort.AUTHOR -> R.string.discover_sort_author
    DiscoverSort.ADDED -> R.string.discover_sort_added
    DiscoverSort.DURATION -> R.string.discover_sort_duration
}

@Composable
private fun AllTitlesHeader(sort: DiscoverSort, onSort: (DiscoverSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.discover_all), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Box {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.discover_sort, stringResource(sort.label()))) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DiscoverSort.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(stringResource(s.label())) }, onClick = { open = false; onSort(s) })
                }
            }
        }
    }
}

@Composable
private fun SeriesTileView(tile: SeriesTile, onClick: () -> Unit) {
    val base = LocalServerBaseUrl.current
    Column(Modifier.width(TILE_WIDTH).clip(CoverShapes.Tile).clickable(onClick = onClick)) {
        CoverArt(tile.cover?.let { c -> coverModel(base, c.id, c.coverPath, c.coverVersion) }, tile.name, Modifier.size(TILE_WIDTH))
        Text(tile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            pluralStringResource(R.plurals.discover_volumes, tile.count, tile.count),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthorTileView(tile: AuthorTile, onClick: () -> Unit) {
    val base = LocalServerBaseUrl.current
    Column(Modifier.width(96.dp).clip(CoverShapes.Tile).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        val url = if (tile.authorId != null && base != null) {
            remember(base, tile.authorId) {
                base.newBuilder().addPathSegments("api/authors").addPathSegment(tile.authorId).addPathSegment("image").build().toString()
            }
        } else null
        CoverArt(url, tile.name, Modifier.size(72.dp), shape = CircleShape, initialsStyle = MaterialTheme.typography.titleMedium)
        Text(tile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            pluralStringResource(R.plurals.browse_title_count, tile.count, tile.count),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
