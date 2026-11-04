package io.wolftown.lauschig.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.entities.CollectionKind
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.local.DeviceTab
import io.wolftown.lauschig.ui.collections.CollectionListTab
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.FilterEmptyState
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.SectionTitle
import io.wolftown.lauschig.ui.common.StatePage
import io.wolftown.lauschig.ui.common.TagFilterChip
import io.wolftown.lauschig.ui.theme.Spacing

@StringRes
private fun LibraryTab.label(): Int = when (this) {
    LibraryTab.InProgress -> R.string.library_tab_in_progress
    LibraryTab.Finished -> R.string.library_tab_finished
    LibraryTab.Downloaded -> R.string.library_tab_downloaded
    LibraryTab.Series -> R.string.library_tab_series
    LibraryTab.Collections -> R.string.library_tab_collections
    LibraryTab.Playlists -> R.string.library_tab_playlists
    LibraryTab.Device -> R.string.library_tab_device
}

/** The empty tab's icon and its line about what would show up there. */
private fun emptyHint(tab: LibraryTab): Pair<ImageVector, Int> = when (tab) {
    LibraryTab.Finished -> Icons.Rounded.TaskAlt to R.string.library_empty_finished
    LibraryTab.Downloaded -> Icons.Rounded.DownloadForOffline to R.string.library_empty_downloaded
    LibraryTab.Series -> Icons.Rounded.CollectionsBookmark to R.string.library_empty_series
    else -> Icons.Rounded.Headphones to R.string.library_empty_in_progress
}

@Composable
fun LibraryRoute(
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCollection: (id: String) -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    LibraryScreen(
        state, viewModel::selectTab, viewModel::setKinds, onOpenItem, modifier, onPlay = actions::play, onMenu = { menuFor = it },
        onTag = viewModel::selectTag,
        collections = { kind -> CollectionListTab(kind, onOpenCollection) },
        device = { DeviceTab(onOpenItem, onPlay = actions::play, onMenu = { menuFor = it }) },
        onOpenDownloads = onOpenDownloads,
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
    /** Content of the "on this device" tab. */
    device: @Composable () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onTag: (String?) -> Unit = {},
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
        if (state.tab == LibraryTab.Device) {
            device()
            return@Column
        }
        KindFilterChips(
            state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(top = 8.dp),
            trailing = if (state.availableTags.isEmpty()) null else ({ TagFilterChip(state.availableTags, state.selectedTag, onTag) }),
        )
        if (state.tab == LibraryTab.Downloaded) {
            TextButton(onClick = onOpenDownloads, modifier = Modifier.padding(horizontal = 8.dp).testTag("openDownloads")) {
                Icon(Icons.Rounded.Downloading, contentDescription = null)
                Text(stringResource(R.string.downloads_manage), Modifier.padding(start = 8.dp))
            }
        }
        val empty = if (state.tab == LibraryTab.Series) state.series.isEmpty() else state.items.isEmpty()
        when {
            empty && !state.loaded -> StatePage { LoadingState(verticalPadding = it) }
            // Empty only because of the kind or tag filter: say so, and offer to reset it.
            empty && state.hiddenByFilter -> StatePage { padding ->
                FilterEmptyState(onReset = { onKinds(emptySet()); onTag(null) }, Modifier.testTag("libraryEmpty"), verticalPadding = padding)
            }
            empty -> StatePage { padding ->
                val (icon, hint) = emptyHint(state.tab)
                EmptyState(icon, stringResource(R.string.library_empty), Modifier.testTag("libraryEmpty"), supporting = stringResource(hint), verticalPadding = padding)
            }
            else -> LibraryGrid(state, tile)
        }
    }
}

@Composable
private fun LibraryGrid(state: LibraryUiState, tile: @Composable (ItemSummary) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tileGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.tileGap),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.tab == LibraryTab.Series) {
            state.series.forEach { group ->
                item(key = "series:${group.name.lowercase()}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionTitle(group.name, Modifier.padding(top = Spacing.s))
                }
                items(group.items, key = ItemSummary::key, contentType = { "tile" }) { tile(it) }
            }
        } else {
            items(state.items, key = ItemSummary::key, contentType = { "tile" }) { tile(it) }
        }
    }
}
