package io.wolftown.lauschig.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.common.TvCardGrid
import io.wolftown.lauschig.tv.common.TvCoverCard
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.ui.browse.BrowseEntry
import io.wolftown.lauschig.ui.browse.BrowseUiState
import io.wolftown.lauschig.ui.browse.BrowseViewModel
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.connection.ConnectionStatusViewModel

internal const val TAG_BROWSE_TITLE = "tvBrowseTitle"

/** A series, author, narrator, genre or tag ([io.wolftown.lauschig.ui.nav.Browse]): the phone's view model, as it is. */
@Composable
fun TvBrowseRoute(
    onOpenItem: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
    connection: ConnectionStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TvBrowseScreen(state, onOpenItem, online = !LocalOfflineDimming.current, onRetry = connection::reconnect)
}

/**
 * Name, number of titles, and the titles as a grid in the order of [BrowseUiState.entries] (a series: by volume). Gaps in
 * a series ([BrowseEntry.Missing]) are left out, and the TV does not ask about them: that question and its answer belong
 * to the phone. The focus starts on the first card.
 */
@Composable
fun TvBrowseScreen(
    state: BrowseUiState,
    onOpenItem: (String) -> Unit,
    online: Boolean = true,
    onRetry: () -> Unit = {},
) {
    val titles = state.entries.filterIsInstance<BrowseEntry.Tile>().map { it.item }
    when {
        !state.loaded -> TvMessage(stringResource(R.string.tv_loading))
        // Read from the device's store: offline, only what never arrived is missing.
        titles.isEmpty() && !online -> TvMessage(stringResource(R.string.tv_browse_offline), stringResource(R.string.tv_retry), onRetry)
        titles.isEmpty() -> TvMessage(stringResource(R.string.tv_browse_empty))
        else -> Column(
            Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                state.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp).semantics { heading() }.testTag(TAG_BROWSE_TITLE),
            )
            Text(
                pluralStringResource(R.plurals.browse_title_count, state.count, state.count),
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TvCardGrid(titles, key = { it.key }, modifier = Modifier.fillMaxWidth().weight(1f)) { item, modifier ->
                TvCoverCard(item, { onOpenItem(item.id) }, modifier)
            }
        }
    }
}
