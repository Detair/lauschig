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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemSummary

@androidx.compose.runtime.Immutable
data class StartUiState(
    val continueItem: ItemSummary? = null,
    val downloaded: List<ItemSummary> = emptyList(),
)

@Composable
fun StartRoute(
    onOpenItem: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("openSettings")) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title))
            }
        }
        StartScreen(state, onOpen = onOpenItem, onPlay = viewModel::play, onMenu = { menuFor = it }, onOpenLibrary = onOpenLibrary, modifier = Modifier.weight(1f))
    }
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem) }
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
) {
    if (state.continueItem == null && state.downloaded.isEmpty()) {
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
        state.continueItem?.let { item ->
            item(key = "h-continue") { SectionHeader(R.string.start_continue) }
            item(key = "continue") { TileRow(listOf(item), onOpen, onPlay, onMenu) }
        }
        if (state.downloaded.isNotEmpty()) {
            item(key = "h-downloaded") { SectionHeader(R.string.start_downloaded) }
            item(key = "downloaded") { TileRow(state.downloaded, onOpen, onPlay, onMenu) }
        }
    }
}

@Composable
private fun SectionHeader(textRes: Int) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
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
