package io.wolftown.lauschig.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.catalog.Facet
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.kindFilterState
import io.wolftown.lauschig.ui.common.toSummary
import io.wolftown.lauschig.ui.library.FilterPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SearchUiState(
    /** What is in the field right now (not debounced). */
    val query: String = "",
    val availableKinds: Set<LibraryKind> = emptySet(),
    val selectedKinds: Set<LibraryKind> = emptySet(),
    /** Recent search terms, shown while the field is empty. */
    val recent: List<String> = emptyList(),
    /** Results belong to [resultsFor]; they stay visible while the next search runs. */
    val resultsFor: String = "",
    val titles: List<ItemSummary> = emptyList(),
    val authors: List<Facet> = emptyList(),
    val narrators: List<Facet> = emptyList(),
    val series: List<Facet> = emptyList(),
    val genres: List<Facet> = emptyList(),
    val tags: List<Facet> = emptyList(),
    /** True once a search for a non-blank query has completed. */
    val searched: Boolean = false,
) {
    val hasResults: Boolean
        get() = titles.isNotEmpty() || authors.isNotEmpty() || narrators.isNotEmpty() || series.isNotEmpty() || genres.isNotEmpty() || tags.isNotEmpty()
}

/** Local search over the Room catalog (title, author, narrator, series, genre, tag); never touches the network. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    accountStore: AccountStore,
    private val catalogDao: CatalogDao,
    private val itemDao: ItemDao,
    private val libraryDao: LibraryDao,
    private val progressDao: ProgressDao,
    private val downloadDao: DownloadDao,
    private val covers: CoverPathCache,
    private val prefs: FilterPreferences,
    private val catalog: Catalog,
    private val recent: RecentSearches,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val serverId: Flow<String?> = accountStore.account.map { it?.serverId }.distinctUntilChanged()
    private var focusPending = true

    val state: StateFlow<SearchUiState> = combine(
        query, serverId.flatMapLatest { sid -> if (sid == null) flowOf(Results()) else resultsFor(sid) }, recent.queries,
    ) { q, r, recents ->
        SearchUiState(
            q, r.available, r.selected, recents, r.query, r.titles, r.authors, r.narrators, r.series, r.genres, r.tags,
            searched = r.query.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    /** IME search action or opening a result: remembers the current term. */
    fun submit() {
        val q = query.value
        viewModelScope.launch { recent.record(q) }
    }

    fun setKinds(kinds: Set<LibraryKind>) {
        viewModelScope.launch { prefs.setKinds(SCREEN, kinds) }
    }

    /** True exactly once per view model, so the keyboard opens when the tab is first shown but not after coming back from a page. */
    fun takeInitialFocus(): Boolean = focusPending.also { focusPending = false }

    private fun resultsFor(sid: String): Flow<Results> {
        val debounced = query.map { it.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0L else DEBOUNCE_MS }
        val itemState = combine(progressDao.observeAll(sid), itemDao.observeDownloaded(sid), downloadDao.observeActiveIds(sid), ::Triple)
        return combine(
            catalogDao.observeAll(sid), kindFilterState(libraryDao, prefs, sid, SCREEN), debounced, itemState,
        ) { all, f, q, (progress, downloaded, active) ->
            val byId = progress.associateBy { it.itemId }
            val downloadedIds = downloaded.mapTo(HashSet()) { it.id }
            covers.sync(downloadedIds)
            val activeIds = active.toHashSet()
            val found = catalog.search(all.filter { it.libraryId in f.libraryIds }, q)
            fun ItemEntity.summary() = toSummary(covers, byId[id], id in downloadedIds, id in activeIds)
            Results(
                f.available, f.selected, q, found.titles.map { it.summary() },
                found.authors, found.narrators, found.series, found.genres, found.tags,
            )
        }.flowOn(Dispatchers.Default)
    }

    private data class Results(
        val available: Set<LibraryKind> = emptySet(),
        val selected: Set<LibraryKind> = emptySet(),
        val query: String = "",
        val titles: List<ItemSummary> = emptyList(),
        val authors: List<Facet> = emptyList(),
        val narrators: List<Facet> = emptyList(),
        val series: List<Facet> = emptyList(),
        val genres: List<Facet> = emptyList(),
        val tags: List<Facet> = emptyList(),
    )

    companion object {
        const val SCREEN = "search"
        const val DEBOUNCE_MS = 200L
    }
}
