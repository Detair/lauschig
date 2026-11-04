package io.wolftown.lauschig.ui.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.ReviewSlide
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.stats.zones
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.nav.YearReviewPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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

/**
 * The year in review of [year], from Room only (offline-first, like the statistics). The slides are built **once** when
 * the page opens: the story does not change under the reader (a title playing meanwhile, a sync writing pages); opened
 * again, it is new. Only the opening's „Noch nicht alle Hörsitzungen geladen“ follows the account's history live (5A's
 * "loading" / "loads on Wi-Fi" / "failed"): it goes once the history is complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    @Inject constructor(
        savedState: SavedStateHandle, accounts: AccountStore, source: ListeningDataSource, calendar: StatsCalendar,
        sync: ListeningSessionSync, covers: CoverPathCache,
    ) : this(savedState.toRoute<YearReviewPage>().year, accounts, source, calendar, sync::historyLoad, covers, Dispatchers.Default, savedState)

    private class Built(val serverId: String?, val state: YearReviewUiState)

    val state: StateFlow<YearReviewUiState> = flow { emit(load()) }
        .flatMapLatest { built ->
            val sid = built.serverId
            if (sid == null || built.state.review == null) flowOf(built.state)
            else historyLoad(sid).map { it != HistoryLoad.NONE }.distinctUntilChanged().map { built.state.withIncomplete(it) }
        }
        .flowOn(dispatcher)
        // Lazily, not while subscribed: back from the background the story is the same one (and in the same place).
        .stateIn(viewModelScope, SharingStarted.Lazily, YearReviewUiState(year))

    private var story: StoryState? = null

    /**
     * The story's place lives here, not in the page's remembered state: turning the device switches the frame between
     * the tab bar and the navigation rail, which composes the navigation host along another path – a page's remembered
     * state does not come along, the view model does. The slide and the entrances played are also written to the
     * route's [SavedStateHandle], so the story goes on where it was after the system ended the process (the running
     * time of the slide starts again).
     */
    fun story(count: Int): StoryState = story?.takeIf { it.count == count } ?: run {
        val saved = savedState.get<Int>(KEY_COUNT) == count
        StoryState(
            count,
            index = if (saved) savedState.get<Int>(KEY_INDEX) ?: 0 else 0,
            shown = if (saved) savedState.get<IntArray>(KEY_SHOWN)?.toSet().orEmpty() else emptySet(),
            onPlace = { index, shown ->
                savedState[KEY_COUNT] = count
                savedState[KEY_INDEX] = index
                savedState[KEY_SHOWN] = shown.toIntArray()
            },
        ).also { story = it }
    }

    private suspend fun load(): Built {
        val sid = accounts.account.first()?.serverId ?: return Built(null, YearReviewUiState(year, loading = false))
        val data = source.observe(sid, calendar.zones()).first()
        val incomplete = historyLoad(sid).first() != HistoryLoad.NONE
        val review = YearReview.build(year, data.sessions, data.finished, data.seriesGroups, data.achievements(), data.items, data.zone, incomplete)
        val ids = review?.slides.orEmpty().flatMapTo(LinkedHashSet()) { it.coverIds() }
        return Built(
            sid,
            YearReviewUiState(
                year, loading = false, review = review,
                covers = ids.associateWith { id -> ReviewCover(covers.pathFor(sid, id), data.items[id]?.coverUpdatedAt ?: 0) },
            ),
        )
    }

    private companion object {
        const val KEY_COUNT = "story_count"
        const val KEY_INDEX = "story_index"
        const val KEY_SHOWN = "story_shown"
    }
}

/** The same slides, the opening saying [incomplete] (the history is still loading) or not. */
private fun YearReviewUiState.withIncomplete(incomplete: Boolean): YearReviewUiState {
    val review = review ?: return this
    return copy(review = review.copy(slides = review.slides.map { if (it is ReviewSlide.Opening) it.copy(incomplete = incomplete) else it }))
}

/** The titles whose covers a slide shows. */
internal fun ReviewSlide.coverIds(): List<String> = when (this) {
    is ReviewSlide.TopTitles -> titles.map { it.itemId }
    is ReviewSlide.TopPeople -> listOfNotNull(authorItemId)
    is ReviewSlide.Finished -> listOfNotNull(first?.itemId, last?.itemId, seriesItemId)
    is ReviewSlide.Closing -> listOfNotNull(topTitle?.itemId)
    else -> emptyList()
}
