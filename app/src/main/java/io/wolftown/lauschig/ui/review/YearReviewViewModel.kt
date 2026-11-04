package io.wolftown.lauschig.ui.review

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.stats.ListeningData
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.ReviewSlide
import io.wolftown.lauschig.stats.SlideKind
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.stats.zones
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.nav.YearReviewPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class YearReviewUiState(
    val year: Int,
    val loading: Boolean = true,
    /** Null: less than an hour in that year (or no account) – the page says so. */
    val review: YearReview? = null,
    /** The covers of the titles the slides show, by item id. */
    val covers: Map<String, ReviewCover> = emptyMap(),
)

/** What the page shows and where its story stands – always replaced together, so the place belongs to these slides. */
class ReviewPage(val state: YearReviewUiState, val story: StoryState?)

/** A story's place on given slides: the slide it stands on and the ones whose entrance has played. */
internal data class StoryPlace(val index: Int, val shown: Set<Int>)

/**
 * The place on the slides [from] (standing on [index], [shown] played) mapped onto the rebuilt slides [to]: the same
 * kind of slide where it still exists, else the slide that now follows in the fixed order (the last one at the end);
 * the played entrances follow their kinds. Without a known kind (an index beyond [from]) the position, kept in range.
 */
internal fun relocate(from: List<SlideKind>, index: Int, shown: Set<Int>, to: List<SlideKind>): StoryPlace {
    if (to.isEmpty()) return StoryPlace(0, emptySet())
    val kind = from.getOrNull(index)
    val at = when {
        kind == null -> index.coerceIn(0, to.lastIndex)
        kind in to -> to.indexOf(kind)
        else -> to.indexOfFirst { it.ordinal > kind.ordinal }.takeIf { it >= 0 } ?: to.lastIndex
    }
    val played = shown.mapNotNullTo(HashSet()) { from.getOrNull(it) }
    return StoryPlace(at, to.indices.filterTo(HashSet()) { to[it] in played })
}

/**
 * The year in review of [year], from Room only (offline-first, like the statistics). Its numbers follow the data: when
 * the history finishes loading (the opening's „Noch nicht alle Hörsitzungen geladen“ goes) or the cache changes (a sync,
 * a title playing), every slide is built again – the story keeps its place on the same kind of slide (see [relocate]),
 * and entrances that played do not play again.
 */
@HiltViewModel
class YearReviewViewModel(
    private val year: Int,
    private val accounts: AccountStore,
    private val source: ListeningDataSource,
    private val calendar: StatsCalendar,
    private val historyLoad: (serverId: String) -> Flow<HistoryLoad>,
    private val covers: CoverPathCache,
    dispatcher: CoroutineDispatcher,
    /** Where the story stands, kept across a process death (the route's own handle). */
    private val savedState: SavedStateHandle,
) : ViewModel() {
    @Inject constructor(
        savedState: SavedStateHandle, accounts: AccountStore, source: ListeningDataSource, calendar: StatsCalendar,
        sync: ListeningSessionSync, covers: CoverPathCache,
    ) : this(savedState.toRoute<YearReviewPage>().year, accounts, source, calendar, sync::historyLoad, covers, Dispatchers.Default, savedState)

    private val _state = MutableStateFlow(YearReviewUiState(year))

    /** The page's data as it is built (tests and anything that does not draw the story). */
    val state: StateFlow<YearReviewUiState> = _state.asStateFlow()

    /** For the page: the data with its story's place, replaced in one snapshot (never a place of other slides). */
    var page by mutableStateOf(ReviewPage(YearReviewUiState(year), null))
        private set

    private var story: StoryState? = null
    private var storyKinds: List<SlideKind> = emptyList()

    init {
        viewModelScope.launch {
            builds().flowOn(dispatcher).distinctUntilChanged().collect { built ->
                Snapshot.withMutableSnapshot {
                    page = ReviewPage(built, built.review?.let { place(it.kinds) })
                }
                _state.value = built
            }
        }
    }

    /** The review again whenever the cached data or the history's state changes (bursts are computed once). */
    private fun builds(): Flow<YearReviewUiState> = flow {
        val sid = accounts.account.first()?.serverId
        if (sid == null) {
            emit(YearReviewUiState(year, loading = false))
            return@flow
        }
        val incomplete = historyLoad(sid).map { it != HistoryLoad.NONE }.distinctUntilChanged()
        emitAll(
            combine(source.observe(sid, calendar.zones()), incomplete, ::Pair)
                .conflate()
                .map { (data, notAll) -> build(sid, data, notAll) },
        )
    }

    private fun build(sid: String, data: ListeningData, incomplete: Boolean): YearReviewUiState {
        val review = YearReview.build(year, data.sessions, data.finished, data.seriesGroups, data.achievements(), data.items, data.zone, incomplete)
        val ids = review?.slides.orEmpty().flatMapTo(LinkedHashSet()) { it.coverIds() }
        return YearReviewUiState(
            year, loading = false, review = review,
            covers = ids.associateWith { id -> ReviewCover(covers.pathFor(sid, id), data.items[id]?.coverUpdatedAt ?: 0) },
        )
    }

    /**
     * The story's place lives here, not in the page's remembered state: turning the device switches the frame between
     * the tab bar and the navigation rail, which composes the navigation host along another path – a page's remembered
     * state does not come along, the view model does. The slides' kinds, the slide and the entrances played are also
     * written to the route's [SavedStateHandle]: after the system ended the process the story goes on at the same kind
     * of slide, even when the rebuilt year has other slides (the running time of the slide starts again).
     */
    private fun place(kinds: List<SlideKind>): StoryState {
        story?.let { s ->
            if (kinds != storyKinds) {
                val moved = relocate(storyKinds, s.index, s.shownSlides, kinds)
                storyKinds = kinds
                s.relocate(kinds.size, moved.index, moved.shown)
            }
            return s
        }
        val savedKinds = savedState.get<IntArray>(KEY_KINDS)?.toList()?.mapNotNull { SlideKind.entries.getOrNull(it) }
        val start = if (savedKinds == null) StoryPlace(0, emptySet())
        else relocate(savedKinds, savedState.get<Int>(KEY_INDEX) ?: 0, savedState.get<IntArray>(KEY_SHOWN)?.toSet().orEmpty(), kinds)
        storyKinds = kinds
        save(start.index, start.shown)
        return StoryState(kinds.size, start.index, shown = start.shown, onPlace = ::save).also { story = it }
    }

    private fun save(index: Int, shown: Set<Int>) {
        savedState[KEY_KINDS] = storyKinds.map { it.ordinal }.toIntArray()
        savedState[KEY_INDEX] = index
        savedState[KEY_SHOWN] = shown.toIntArray()
    }

    private companion object {
        const val KEY_KINDS = "story_kinds"
        const val KEY_INDEX = "story_index"
        const val KEY_SHOWN = "story_shown"
    }
}

/** The titles whose covers a slide shows. */
internal fun ReviewSlide.coverIds(): List<String> = when (this) {
    is ReviewSlide.TopTitles -> titles.map { it.itemId }
    is ReviewSlide.TopPeople -> listOfNotNull(authorItemId)
    is ReviewSlide.Finished -> listOfNotNull(first?.itemId, last?.itemId, seriesItemId)
    is ReviewSlide.Closing -> listOfNotNull(topTitle?.itemId)
    else -> emptyList()
}
