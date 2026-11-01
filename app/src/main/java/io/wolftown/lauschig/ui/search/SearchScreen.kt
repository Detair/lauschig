package io.wolftown.lauschig.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.nav.BrowseKind

@Composable
fun SearchRoute(
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ItemSummary?>(null) }
    val autoFocus = remember { viewModel.takeInitialFocus() }
    SearchScreen(
        state, viewModel::setQuery, viewModel::submit, viewModel::setKinds,
        onRecent = { viewModel.setQuery(it); viewModel.submit() },
        onOpenItem = { viewModel.submit(); onOpenItem(it) },
        onOpenBrowse = { kind, name -> viewModel.submit(); onOpenBrowse(kind, name) },
        modifier = modifier, autoFocus = autoFocus, onPlay = actions::play, onMenu = { menuFor = it },
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = onOpenItem, viewModel = actions) }
}

/** Search field, kind filter, then either the recent terms (empty field) or the sections of the results. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onKinds: (Set<LibraryKind>) -> Unit,
    onRecent: (String) -> Unit,
    onOpenItem: (itemId: String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onPlay: (itemId: String) -> Unit = {},
    onMenu: (ItemSummary) -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery(""); focus.requestFocus() }) { Icon(Icons.Rounded.Close, stringResource(R.string.search_clear)) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(); focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.query.isBlank()) {
                if (state.recent.isNotEmpty()) {
                    header("recentHeader", R.string.search_recent)
                    full("recent") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.recent.forEach { AssistChip(onClick = { onRecent(it) }, label = { Text(it) }) }
                        }
                    }
                }
            } else if (state.searched && !state.hasResults) {
                full("empty") {
                    Text(
                        stringResource(R.string.search_nothing_found, state.resultsFor),
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                facets("authors", R.string.search_authors, state.authors, BrowseKind.AUTHOR, onOpenBrowse)
                facets("narrators", R.string.search_narrators, state.narrators, BrowseKind.NARRATOR, onOpenBrowse)
                facets("series", R.string.search_series, state.series, BrowseKind.SERIES, onOpenBrowse)
                facets("genres", R.string.search_genres, state.genres, BrowseKind.GENRE, onOpenBrowse)
                facets("tags", R.string.search_tags, state.tags, BrowseKind.TAG, onOpenBrowse)
                if (state.titles.isNotEmpty()) {
                    header("titlesHeader", R.string.search_titles)
                    items(state.titles, key = { it.key }, contentType = { "tile" }) { item ->
                        CoverTile(item, onClick = { onOpenItem(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) })
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.header(key: String, title: Int) = full(key) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** Section of chips ("name · count"); tapping one opens the matching entity page. */
private fun LazyGridScope.facets(key: String, title: Int, list: List<Facet>, kind: BrowseKind, onOpen: (BrowseKind, String) -> Unit) {
    if (list.isEmpty()) return
    header("${key}Header", title)
    full(key) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { f -> AssistChip(onClick = { onOpen(kind, f.name) }, label = { Text("${f.name} · ${f.count}") }) }
        }
    }
}
