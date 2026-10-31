package io.wolftown.lauschig.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemSummary

@androidx.compose.runtime.Immutable
data class StartUiState(
    val continueItem: ItemSummary? = null,
    val downloaded: List<ItemSummary> = emptyList(),
)

@Composable
fun StartRoute(modifier: Modifier = Modifier, viewModel: StartViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StartScreen(state, onPlay = viewModel::play, modifier = modifier)
}

@Composable
fun StartScreen(state: StartUiState, onPlay: (itemId: String) -> Unit, modifier: Modifier = Modifier) {
    if (state.continueItem == null && state.downloaded.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.start_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.continueItem?.let { item ->
            item(key = "h-continue") { SectionHeader(R.string.start_continue) }
            item(key = "continue") { TileRow(listOf(item), onPlay) }
        }
        if (state.downloaded.isNotEmpty()) {
            item(key = "h-downloaded") { SectionHeader(R.string.start_downloaded) }
            item(key = "downloaded") { TileRow(state.downloaded, onPlay) }
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
private fun TileRow(items: List<ItemSummary>, onPlay: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = ItemSummary::key, contentType = { "tile" }) { item ->
            CoverTile(item, Modifier.width(140.dp), onClick = { onPlay(item.id) })
        }
    }
}
