package io.wolftown.lauschig.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemSummary

@androidx.compose.runtime.Immutable
data class StartUiState(
    val continueItems: List<ItemSummary> = emptyList(),
    val nextInSeries: List<ItemSummary> = emptyList(),
    val recentlyAdded: List<ItemSummary> = emptyList(),
    val downloaded: List<ItemSummary> = emptyList(),
    /** Episode tiles: podcast cover, episode title, id = episode key. Empty without a podcast library. */
    val newEpisodes: List<ItemSummary> = emptyList(),
)

@Composable
fun StartRoute(
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    // An episode tile opens its podcast.
    val onOpen: (String) -> Unit = { onOpenItem(PlayableKey.parse(it).libraryItemId) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("openSettings")) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
            }
        }
        StartScreen(state, onOpen = onOpen, onPlay = viewModel::play, onMenu = { menuFor = it }, onOpenLibrary = onOpenLibrary, onOpenDiscover = onOpenDiscover, modifier = Modifier.weight(1f))
    }
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpen) }
}

/** Tap opens the details, the play button plays, long-press opens the actions menu. */
@Composable
fun StartScreen(
    state: StartUiState,
    onOpen: (itemId: String) -> Unit,
    onPlay: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (ItemSummary) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
) {
    if (state.continueItems.isEmpty() && state.nextInSeries.isEmpty() && state.recentlyAdded.isEmpty() && state.downloaded.isEmpty() && state.newEpisodes.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.start_empty), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 16.dp).testTag("openLibrary")) {
                Text(stringResource(R.string.start_open_library))
            }
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        section("continue", R.string.start_continue, state.continueItems, onOpenLibrary, onOpen, onPlay, onMenu)
        section("episodes", R.string.start_new_episodes, state.newEpisodes, null, onOpen, onPlay, onMenu)
        section("next", R.string.start_next_in_series, state.nextInSeries, null, onOpen, onPlay, onMenu)
        section("recent", R.string.start_recently_added, state.recentlyAdded, onOpenDiscover, onOpen, onPlay, onMenu)
        section("downloaded", R.string.start_downloaded, state.downloaded, null, onOpen, onPlay, onMenu)
    }
}

/** A header (with an optional "See all" link) plus a tile row; empty sections are left out. */
private fun LazyListScope.section(
    key: String, titleRes: Int, items: List<ItemSummary>, onSeeAll: (() -> Unit)?,
    onOpen: (String) -> Unit, onPlay: (String) -> Unit, onMenu: (ItemSummary) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "h-$key", contentType = "header") { SectionHeader(titleRes, onSeeAll, Modifier.testTag("seeAll-$key")) }
    item(key = key, contentType = "row") { TileRow(items, onOpen, onPlay, onMenu) }
}

@Composable
private fun SectionHeader(textRes: Int, onSeeAll: (() -> Unit)?, seeAllModifier: Modifier) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (onSeeAll != null) TextButton(onClick = onSeeAll, modifier = seeAllModifier) { Text(stringResource(R.string.start_see_all)) }
    }
}

@Composable
private fun TileRow(items: List<ItemSummary>, onOpen: (String) -> Unit, onPlay: (String) -> Unit, onMenu: (ItemSummary) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = ItemSummary::key, contentType = { "tile" }) { item ->
            CoverTile(
                item, Modifier.width(140.dp),
                onClick = { onOpen(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) },
            )
        }
    }
}
