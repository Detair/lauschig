package io.wolftown.lauschig.tv.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Icon
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.common.HidePodcasts
import io.wolftown.lauschig.tv.common.TvCardGrid
import io.wolftown.lauschig.tv.common.TvCoverCard
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.tv.common.TvSeriesCard
import io.wolftown.lauschig.tv.home.rememberReconnectThenRefresh
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.connection.ConnectionStatusViewModel
import io.wolftown.lauschig.ui.discover.DiscoverSort
import io.wolftown.lauschig.ui.discover.DiscoverUiState
import io.wolftown.lauschig.ui.discover.DiscoverViewModel

internal const val TAG_SHOW_TITLES = "tvLibraryTitles"
internal const val TAG_SHOW_SERIES = "tvLibrarySeries"
/** A sort chip is tagged with this plus the [DiscoverSort]'s name. */
internal const val TAG_SORT = "tvLibrarySort:"
internal const val TAG_LIBRARY_NO_SERIES = "tvLibraryNoSeries"
/** The check mark inside a chosen selection button. */
internal const val TAG_CHOSEN = "tvChosen"

/** The library's data: the whole catalog of "Discover", its sort; whether titles or series show is the page's own. */
@Composable
fun TvLibraryRoute(
    onOpenItem: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    discover: DiscoverViewModel = hiltViewModel(),
    connection: ConnectionStatusViewModel = hiltViewModel(),
) {
    val state by discover.state.collectAsStateWithLifecycle()
    val syncing by discover.syncing.collectAsStateWithLifecycle()
    var showSeries by rememberSaveable { mutableStateOf(false) }
    HidePodcasts(state.availableKinds, state.selectedKinds, discover::setKinds)
    val online = !LocalOfflineDimming.current
    TvLibraryScreen(
        state = state,
        showSeries = showSeries,
        onShowSeries = { showSeries = it },
        onSort = discover::setSort,
        onOpenItem = onOpenItem,
        onOpenSeries = onOpenSeries,
        online = online,
        onRetry = rememberReconnectThenRefresh(online, connection::reconnect, discover::refreshLibrary),
        syncing = syncing,
    )
}

/**
 * All titles as a grid (TV spec: "Bibliothek"), or all series. Above the grid: titles or series, and for the titles the
 * sort. The focus starts on the first card; up reaches the controls. While the library loads, when it is empty, and offline
 * with nothing on the TV, a message says so, as on the start page.
 */
@Composable
fun TvLibraryScreen(
    state: DiscoverUiState,
    showSeries: Boolean,
    onShowSeries: (Boolean) -> Unit,
    onSort: (DiscoverSort) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    /** A library sync is waiting or running: with nothing to show yet, the library is not empty but on its way. */
    syncing: Boolean = false,
) {
    when {
        state.loading -> TvMessage(stringResource(R.string.tv_loading))
        state.all.isEmpty() && !online -> TvMessage(stringResource(R.string.tv_home_offline), stringResource(R.string.tv_retry), onRetry)
        state.all.isEmpty() && syncing -> TvMessage(stringResource(R.string.tv_home_loading))
        state.all.isEmpty() -> TvMessage(stringResource(R.string.tv_home_empty))
        else -> LibraryContent(state, showSeries, onShowSeries, onSort, onOpenItem, onOpenSeries)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryContent(
    state: DiscoverUiState,
    showSeries: Boolean,
    onShowSeries: (Boolean) -> Unit,
    onSort: (DiscoverSort) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    // The grid takes the focus when the page comes (also back from a title), not when the controls above switch it.
    val started = remember { Started() }
    val takeFocus = !started.value
    SideEffect { started.value = true }
    val titlesChip = remember { FocusRequester() }
    val seriesChip = remember { FocusRequester() }
    // Whether the grid holds the focus; a plain field, as it must still say so while the grid is being taken away.
    val gridFocus = remember { Started() }
    // The series vanish (a sync) while one of them has the focus: decided while composing, before the grid leaves.
    val refocusSeries = showSeries && state.series.isEmpty() && gridFocus.value
    Column(Modifier.fillMaxSize().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Two groups that wrap as a whole: the open rail leaves less room, and a group must not be squeezed.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
                // Up from anywhere in the grid: to the chosen "Show" button, not to whichever button is above the card.
                .focusProperties { onEnter = { (if (showSeries) seriesChip else titlesChip).requestFocus() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.tv_library_show))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.search_titles), !showSeries, TAG_SHOW_TITLES, Modifier.focusRequester(titlesChip)) { onShowSeries(false) }
                    Chip(stringResource(R.string.discover_series), showSeries, TAG_SHOW_SERIES, Modifier.focusRequester(seriesChip)) { onShowSeries(true) }
                }
            }
            // The sort applies to the titles only; the series keep their order.
            if (!showSeries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(R.string.tv_library_sort))
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DiscoverSort.entries.forEach { sort ->
                            Chip(stringResource(sort.label()), state.sort == sort, TAG_SORT + sort.name) { onSort(sort) }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { gridFocus.value = it.hasFocus }) {
            when {
                !showSeries -> key(state.sort) {
                    // A new order starts at its top, not at the card that had the focus in the old one.
                    TvCardGrid(state.all, key = { it.key }, takeFocus = takeFocus) { item, modifier ->
                        TvCoverCard(item, { onOpenItem(item.id) }, modifier)
                    }
                }
                state.series.isNotEmpty() -> TvCardGrid(state.series, key = { it.name }, takeFocus = takeFocus) { tile, modifier ->
                    TvSeriesCard(tile, { onOpenSeries(tile.name) }, modifier)
                }
                // Plain text, not a message that takes the focus: the person is on the switch above. If the focus was in
                // the series that went, it goes to that switch, the nearest thing left.
                else -> {
                    if (refocusSeries) LaunchedEffect(Unit) { seriesChip.requestFocusWhenPlaced() }
                    Text(
                    stringResource(R.string.tv_browse_empty),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center).testTag(TAG_LIBRARY_NO_SERIES),
                    )
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 12.dp),
    )
}

/**
 * One choice of a group: filled and with a check mark while chosen. The mark stays when the focus lights the button up, so
 * chosen or not shows also on the button with the focus. One button whatever its state, so pressing it keeps the focus.
 */
@Composable
private fun Chip(text: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected }.testTag(tag),
        colors = if (selected) {
            ButtonDefaults.colors(containerColor = scheme.primary, contentColor = scheme.onPrimary)
        } else {
            ButtonDefaults.colors(containerColor = scheme.surfaceVariant, contentColor = scheme.onSurfaceVariant)
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp).testTag(TAG_CHOSEN))
            Spacer(Modifier.width(6.dp))
        }
        Text(text)
    }
}

private fun DiscoverSort.label(): Int = when (this) {
    DiscoverSort.TITLE -> R.string.discover_sort_title
    DiscoverSort.AUTHOR -> R.string.discover_sort_author
    DiscoverSort.ADDED -> R.string.discover_sort_added
    DiscoverSort.DURATION -> R.string.discover_sort_duration
}

/** Whether the page has been shown; a plain field, not state: only read while composing. */
private class Started {
    var value = false
}
