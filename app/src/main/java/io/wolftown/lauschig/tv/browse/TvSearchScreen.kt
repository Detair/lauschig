package io.wolftown.lauschig.tv.browse

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.tv.common.TvCardRow
import io.wolftown.lauschig.tv.common.TvCoverCard
import io.wolftown.lauschig.tv.common.TvFacetCard
import io.wolftown.lauschig.tv.common.TvTextField
import io.wolftown.lauschig.tv.nav.LocalRailHasFocus
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.search.SearchUiState
import io.wolftown.lauschig.ui.search.SearchViewModel

internal const val TAG_SEARCH_FIELD = "tvSearchField"
internal const val TAG_SEARCH_EMPTY = "tvSearchEmpty"
internal const val TAG_SEARCH_SEARCHING = "tvSearchSearching"
/** A recent search's button is tagged with this plus the term. */
internal const val TAG_RECENT = "tvSearchRecent:"
/** A result row is tagged with this plus its key: titles, series, authors, narrators. */
internal const val TAG_SEARCH_ROW = "tvSearchRow:"

/**
 * Search over the library on this TV (the phone's view model: it searches the local catalog while one types and never
 * needs the server). Opening a result remembers the term, as on the phone.
 */
@Composable
fun TvSearchRoute(
    onOpenItem: (String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TvSearchScreen(
        state = state,
        onQuery = viewModel::setQuery,
        onSubmit = viewModel::submit,
        onOpenItem = { viewModel.submit(); onOpenItem(it) },
        onOpenBrowse = { kind, name -> viewModel.submit(); onOpenBrowse(kind, name) },
    )
}

/**
 * The search (TV spec: a text field with the TV's keyboard, the results as rows). The focus starts on the field; OK there
 * opens the keyboard, whose search key calls [onSubmit] and brings the focus back to the field, from where down reaches the
 * results: rows of titles, series, authors and narrators. A series, author or narrator opens its page. Before a search the
 * recent ones are buttons; a search without hits says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenBrowse: (BrowseKind, String) -> Unit,
) {
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val railHasFocus = LocalRailHasFocus.current
    LaunchedEffect(Unit) { if (!railHasFocus()) field.requestFocus() }
    val shownResults = state.titles.isNotEmpty() || state.series.isNotEmpty() || state.authors.isNotEmpty() || state.narrators.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.widthIn(max = FIELD_WIDTH).padding(end = 12.dp)) {
            TvTextField(
                value = state.query,
                onValueChange = onQuery,
                label = stringResource(R.string.nav_search),
                tag = TAG_SEARCH_FIELD,
                focusRequester = field,
                editing = editing,
                onEditingChange = { editing = it },
                imeAction = ImeAction.Search,
                onImeAction = {
                    onSubmit()
                    // Out of the text, so the keyboard goes; down from the field then reaches the results.
                    editing = false
                    field.requestFocus()
                },
                placeholder = stringResource(R.string.search_hint),
            )
        }
        when {
            state.query.isBlank() -> if (state.recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Heading(stringResource(R.string.search_recent))
                    val first = remember { FocusRequester() }
                    FlowRow(
                        // Down from the wide field: to the first term, not to the one nearest its middle.
                        Modifier.focusProperties { onEnter = { first.requestFocus() } }.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        state.recent.forEachIndexed { i, term ->
                            OutlinedButton(
                                onClick = {
                                    onQuery(term)
                                    onSubmit()
                                    // The recent searches give way to the results: the focus waits on the field, not in nothing.
                                    field.requestFocus()
                                },
                                modifier = Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).testTag(TAG_RECENT + term),
                            ) { Text(term) }
                        }
                    }
                }
            }
            shownResults -> Results(state, onOpenItem, onOpenBrowse)
            state.searched -> Text(
                stringResource(R.string.search_nothing_found, state.resultsFor),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(TAG_SEARCH_EMPTY),
            )
            // The first search for this term is still running.
            else -> Text(
                stringResource(R.string.tv_search_searching),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TAG_SEARCH_SEARCHING),
            )
        }
    }
}

private val FIELD_WIDTH = 640.dp

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

/** One row of results. */
private class ResultRow(val key: String, val content: @Composable (Modifier) -> Unit)

@Composable
private fun Results(state: SearchUiState, onOpenItem: (String) -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    val volumes = @Composable { f: Facet -> pluralStringResource(R.plurals.discover_volumes, f.count, f.count) }
    val titles = @Composable { f: Facet -> pluralStringResource(R.plurals.browse_title_count, f.count, f.count) }
    val facetRow = { key: String, heading: Int, facets: List<Facet>, kind: BrowseKind, count: @Composable (Facet) -> String ->
        ResultRow(key) { modifier ->
            TvCardRow(stringResource(heading), facets, key = { it.name }, modifier = modifier) { facet, cardModifier ->
                TvFacetCard(facet.name, count(facet), facet.coverItemId, "$key:${facet.name}", { onOpenBrowse(kind, facet.name) }, cardModifier)
            }
        }.takeIf { facets.isNotEmpty() }
    }
    val rows = listOfNotNull(
        ResultRow("titles") { modifier ->
            TvCardRow(stringResource(R.string.search_titles), state.titles, key = { it.key }, modifier = modifier) { item, cardModifier ->
                TvCoverCard(item, { onOpenItem(item.id) }, cardModifier)
            }
        }.takeIf { state.titles.isNotEmpty() },
        facetRow("series", R.string.search_series, state.series, BrowseKind.SERIES, volumes),
        facetRow("authors", R.string.search_authors, state.authors, BrowseKind.AUTHOR, titles),
        facetRow("narrators", R.string.search_narrators, state.narrators, BrowseKind.NARRATOR, titles),
    )
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.key }) { row -> row.content(Modifier.testTag(TAG_SEARCH_ROW + row.key)) }
    }
}
