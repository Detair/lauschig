package io.wolftown.lauschig.tv.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.tv.common.TvCardRow
import io.wolftown.lauschig.tv.common.TvCoverCard
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.tv.common.TvSeriesCard
import io.wolftown.lauschig.tv.common.bringIntoReach
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.discover.DiscoverViewModel
import io.wolftown.lauschig.ui.discover.SeriesTile
import io.wolftown.lauschig.ui.start.StartUiState
import io.wolftown.lauschig.ui.start.StartViewModel

/** A row is tagged with this plus its key. */
internal const val TAG_ROW = "tvRow:"

/**
 * The start page's data: the phone's start rows and the series of "Discover". Offline counts once it has settled, as the
 * phone's dimming does: the first check after the start must not show "cannot be reached".
 */
@Composable
fun TvHomeRoute(
    onOpenItem: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    start: StartViewModel = hiltViewModel(),
    discover: DiscoverViewModel = hiltViewModel(),
) {
    val state by start.state.collectAsStateWithLifecycle()
    val discoverState by discover.state.collectAsStateWithLifecycle()
    // "Continue" may have moved on while the page was away, as on the phone.
    LifecycleResumeEffect(start) {
        start.onResume()
        onPauseOrDispose {}
    }
    TvHomeScreen(
        state = state,
        series = discoverState.series,
        online = !LocalOfflineDimming.current,
        onRetry = discover::refreshLibrary,
        // An episode's tile opens its podcast, as on the phone.
        onOpenItem = { onOpenItem(PlayableKey.parse(it).libraryItemId) },
        onOpenSeries = onOpenSeries,
    )
}

/** One row of the start page. */
private class HomeRow(val key: String, val content: @Composable (Modifier, FocusRequester) -> Unit)

/**
 * The start page (TV spec E9): rows "Continue", "Next in your series", "New on the server" and "Series"; a row without
 * titles is left out. The focus starts on the first card of the first row, and coming back from another page it is on the
 * card it was on. While the library loads, when it is empty, and offline without anything to show, a message says so.
 */
@Composable
fun TvHomeScreen(
    state: StartUiState,
    series: List<SeriesTile>,
    online: Boolean,
    onRetry: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    val itemRow = { key: String, title: Int, items: List<ItemSummary> ->
        HomeRow(key) { modifier, focus ->
            TvCardRow(stringResource(title), items, key = { it.key }, modifier = modifier, focusRequester = focus) { item, cardModifier ->
                TvCoverCard(item, { onOpenItem(item.id) }, cardModifier)
            }
        }
    }
    val rows = listOfNotNull(
        itemRow("continue", R.string.start_continue, state.continueItems).takeIf { state.continueItems.isNotEmpty() },
        itemRow("next", R.string.start_next_in_series, state.nextInSeries).takeIf { state.nextInSeries.isNotEmpty() },
        itemRow("recent", R.string.start_recently_added, state.recentlyAdded).takeIf { state.recentlyAdded.isNotEmpty() },
        HomeRow("series") { modifier, focus ->
            TvCardRow(stringResource(R.string.discover_series), series, key = { it.name }, modifier = modifier, focusRequester = focus) { tile, cardModifier ->
                TvSeriesCard(tile, { onOpenSeries(tile.name) }, cardModifier)
            }
        }.takeIf { series.isNotEmpty() },
    )
    when {
        state.loading -> TvMessage(stringResource(R.string.tv_home_loading))
        rows.isNotEmpty() -> HomeRows(rows)
        online -> TvMessage(stringResource(R.string.tv_home_empty))
        else -> TvMessage(stringResource(R.string.tv_home_offline), stringResource(R.string.tv_retry), onRetry)
    }
}

/**
 * The rows, and where the focus goes among them: on entering to the row it was in (the row keeps its card), the first one at
 * first. When the row with the focus goes (it was emptied), the focus moves to the row now in its place instead of getting lost.
 */
@Composable
private fun HomeRows(rows: List<HomeRow>) {
    var focusedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    val keys = rows.map { it.key }
    val requesters = remember { RowFocus() }
    val columnState = rememberLazyListState()
    val pending = remember { Pending() }
    // Decided while composing, before the row leaves: afterwards the focus is already gone. The focus goes to the row now in
    // its place, or the one above when it was the last.
    if (inside && focusedRow != null && focusedRow !in keys) {
        pending.refocus = keys.getOrNull(pending.shown.indexOf(focusedRow).coerceIn(0, keys.lastIndex))
    }
    SideEffect { pending.shown = keys }
    LaunchedEffect(keys) {
        if (pending.entered && pending.refocus == null) return@LaunchedEffect
        // A lazy list drops the row that went when it lays itself out, and the focus with it: ask after that.
        if (pending.refocus != null) withFrameNanos { }
        val key = pending.refocus ?: focusedRow?.takeIf { it in keys } ?: keys.first()
        pending.entered = true
        pending.refocus = null
        columnState.bringIntoReach(keys.indexOf(key))
        requesters.of(key).requestFocusWhenPlaced()
    }
    LazyColumn(
        Modifier.fillMaxSize().onFocusChanged { inside = it.hasFocus },
        state = columnState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            row.content(
                Modifier.testTag(TAG_ROW + row.key).onFocusChanged { if (it.hasFocus) focusedRow = row.key },
                requesters.of(row.key),
            )
        }
    }
}

/** One requester per row, kept for as long as the page: it reaches the card the row remembers. */
private class RowFocus {
    private val byRow = HashMap<String, FocusRequester>()

    fun of(key: String): FocusRequester = byRow.getOrPut(key) { FocusRequester() }
}

/** What the focus still has to do; plain fields, not state: they are read and written around a composition. */
private class Pending {
    var entered = false
    /** The row to move the focus to, as the one it was in has gone. */
    var refocus: String? = null
    /** The rows as last shown. */
    var shown: List<String> = emptyList()
}
