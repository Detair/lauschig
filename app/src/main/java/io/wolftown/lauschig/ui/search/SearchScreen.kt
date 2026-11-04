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
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.common.FilterEmptyState
import io.wolftown.lauschig.ui.common.ItemActionsSheet
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.KindFilterChips
import io.wolftown.lauschig.ui.common.LoadingState
import io.wolftown.lauschig.ui.common.SectionTitle
import io.wolftown.lauschig.ui.common.kindFilterActive
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.theme.Spacing

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
    val openItem: (String) -> Unit = { viewModel.submit(); onOpenItem(it) }
    val autoFocus = remember { viewModel.takeInitialFocus() }
    SearchScreen(
        state, viewModel::setQuery, viewModel::submit, viewModel::setKinds,
        onRecent = { viewModel.setQuery(it); viewModel.submit() },
        onOpenItem = openItem,
        onOpenBrowse = { kind, name -> viewModel.submit(); onOpenBrowse(kind, name) },
        modifier = modifier, autoFocus = autoFocus, onPlay = actions::play, onMenu = { menuFor = it },
    )
    menuFor?.let { ItemActionsSheet(it, onDismiss = { menuFor = null }, onOpenDetails = openItem, viewModel = actions) }
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
    var titlesExpanded by remember { mutableStateOf(false) }
    var expandedFacets by remember { mutableStateOf(emptySet<String>()) }
    val expandFacet: (String) -> Unit = { expandedFacets = expandedFacets + it }

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s).focusRequester(focus),
        )
        KindFilterChips(state.availableKinds, state.selectedKinds, onKinds, Modifier.padding(bottom = Spacing.s))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.tileGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.tileGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.query.isBlank()) {
                if (state.recent.isNotEmpty()) {
                    header("recentHeader", R.string.search_recent)
                    full("recent") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            state.recent.forEach { AssistChip(onClick = { onRecent(it) }, label = { Text(it) }) }
                        }
                    }
                }
            } else if (state.searched && !state.hasResults && kindFilterActive(state.availableKinds, state.selectedKinds)) {
                full("empty") {
                    FilterEmptyState(
                        onReset = { onKinds(emptySet()) }, Modifier.testTag("searchEmpty"),
                        headline = stringResource(R.string.search_nothing_found, state.resultsFor),
                    )
                }
            } else if (state.searched && !state.hasResults) {
                full("empty") {
                    EmptyState(
                        Icons.Rounded.SearchOff, stringResource(R.string.search_nothing_found, state.resultsFor), Modifier.testTag("searchEmpty"),
                        supporting = stringResource(R.string.search_nothing_found_hint),
                    )
                }
            } else if (!state.searched && !state.hasResults) {
                // The first search for this query is still running.
                full("loading") { LoadingState() }
            } else {
                if (state.titles.isNotEmpty()) {
                    header("titlesHeader", R.string.search_titles)
                    val shown = if (titlesExpanded) state.titles else state.titles.take(TITLES_COLLAPSED)
                    items(shown, key = { it.key }, contentType = { "tile" }) { item ->
                        CoverTile(item, onClick = { onOpenItem(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) })
                    }
                    if (shown.size < state.titles.size) {
                        full("titlesMore") {
                            TextButton(onClick = { titlesExpanded = true }, modifier = Modifier.testTag("showAllTitles")) {
                                Text(pluralStringResource(R.plurals.search_show_all_titles, state.titles.size, state.titles.size))
                            }
                        }
                    }
                }
                facets("authors", R.string.search_authors, state.authors, BrowseKind.AUTHOR, onOpenBrowse, "authors" in expandedFacets, expandFacet)
                facets("narrators", R.string.search_narrators, state.narrators, BrowseKind.NARRATOR, onOpenBrowse, "narrators" in expandedFacets, expandFacet)
                facets("series", R.string.search_series, state.series, BrowseKind.SERIES, onOpenBrowse, "series" in expandedFacets, expandFacet)
                facets("genres", R.string.search_genres, state.genres, BrowseKind.GENRE, onOpenBrowse, "genres" in expandedFacets, expandFacet)
                facets("tags", R.string.search_tags, state.tags, BrowseKind.TAG, onOpenBrowse, "tags" in expandedFacets, expandFacet)
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

private fun LazyGridScope.header(key: String, title: Int) = full(key) {
    SectionTitle(stringResource(title), Modifier.padding(top = Spacing.s))
}

private const val TITLES_COLLAPSED = 6
private const val FACETS_COLLAPSED = 12

/** Section of chips ("name · count"), the first [FACETS_COLLAPSED] until expanded by the "+n" chip; tapping one opens the matching entity page. */
private fun LazyGridScope.facets(
    key: String, title: Int, list: List<Facet>, kind: BrowseKind, onOpen: (BrowseKind, String) -> Unit,
    expanded: Boolean, onExpand: (String) -> Unit,
) {
    if (list.isEmpty()) return
    header("${key}Header", title)
    full(key) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            val shown = if (expanded) list else list.take(FACETS_COLLAPSED)
            shown.forEach { f -> AssistChip(onClick = { onOpen(kind, f.name) }, label = { Text("${f.name} · ${f.count}") }) }
            if (shown.size < list.size) {
                AssistChip(onClick = { onExpand(key) }, label = { Text(stringResource(R.string.search_more_chip, list.size - shown.size)) })
            }
        }
    }
}
