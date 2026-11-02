package io.wolftown.lauschig.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.collections.CollectionListTab
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips

@StringRes
private fun LibraryTab.label(): Int = when (this) {
    LibraryTab.InProgress -> R.string.library_tab_in_progress
    LibraryTab.Finished -> R.string.library_tab_finished
    LibraryTab.Downloaded -> R.string.library_tab_downloaded
    LibraryTab.Series -> R.string.library_tab_series
    LibraryTab.Collections -> R.string.library_tab_collections
    LibraryTab.Playlists -> R.string.library_tab_playlists
}

@Composable
fun LibraryRoute(
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCollection: (id: String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    LibraryScreen(
        state, viewModel::selectTab, viewModel::setKinds, onOpenItem, modifier, onPlay = actions::play, onMenu = { menuFor = it },
        collections = { kind -> CollectionListTab(kind, onOpenCollection) },
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onTab: (LibraryTab) -> Unit,
    onKinds: (Set<LibraryKind>) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (itemId: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
    /** Content of the collections / playlists tabs. */
    collections: @Composable (CollectionKind) -> Unit = {},
) {
    val tile: @Composable (ItemSummary) -> Unit = { item ->
        CoverTile(item, onClick = { onOpenItem(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) })
    }
    Column(modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
            LibraryTab.entries.forEach { t ->
                Tab(selected = t == state.tab, onClick = { onTab(t) }, text = { Text(stringResource(t.label())) })
            }
        }
        val listKind = when (state.tab) {
            LibraryTab.Collections -> CollectionKind.COLLECTION
            LibraryTab.Playlists -> CollectionKind.PLAYLIST
            else -> null
        }
        if (listKind != null) {
            collections(listKind)
            return@Column
        }
        KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(top = 8.dp))
        val empty = if (state.tab == LibraryTab.Series) state.series.isEmpty() else state.items.isEmpty()
        if (empty && state.loaded) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LibraryGrid(state, tile)
        }
    }
}

@Composable
private fun LibraryGrid(state: LibraryUiState, tile: @Composable (ItemSummary) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.tab == LibraryTab.Series) {
            state.series.forEach { group ->
                item(key = "series:${group.name.lowercase()}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(group.items, key = ItemSummary::key, contentType = { "tile" }) { tile(it) }
            }
        } else {
            items(state.items, key = ItemSummary::key, contentType = { "tile" }) { tile(it) }
        }
    }
}
