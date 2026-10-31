package io.wolftown.lauschig.ui.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R

/** Phase-1 plain list row; Task 12 replaces it with a cover tile. */
data class StartRow(val itemId: String, val title: String, val author: String?, val progressText: String?)

data class StartUiState(
    val continueItem: StartRow? = null,
    val downloaded: List<StartRow> = emptyList(),
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
        state.continueItem?.let { row ->
            item(key = "h-continue") { SectionHeader(R.string.start_continue) }
            item(key = "continue-${row.itemId}") { BookRow(row, onPlay) }
        }
        if (state.downloaded.isNotEmpty()) {
            item(key = "h-downloaded") { SectionHeader(R.string.start_downloaded) }
            items(state.downloaded, key = { "dl-${it.itemId}" }) { BookRow(it, onPlay) }
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
private fun BookRow(row: StartRow, onPlay: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable { onPlay(row.itemId) }.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(row.author, row.progressText).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(
                sub,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
