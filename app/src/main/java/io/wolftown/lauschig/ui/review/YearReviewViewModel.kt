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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
 * The year in review of [year], from Room only (offline-first, like the statistics). Built **once** when the page
 * opens: the story does not change under the reader (a title playing meanwhile, a sync writing pages); opened again,
 * it is new. A history that is not fully loaded yet (5A's "Hörhistorie wird geladen" / "lädt im WLAN") is said on the
 * opening slide instead of showing smaller totals silently.
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
) : ViewModel() {
    @Inject constructor(
        savedState: SavedStateHandle, accounts: AccountStore, source: ListeningDataSource, calendar: StatsCalendar,
        sync: ListeningSessionSync, covers: CoverPathCache,
    ) : this(savedState.toRoute<YearReviewPage>().year, accounts, source, calendar, sync::historyLoad, covers, Dispatchers.Default)

    val state: StateFlow<YearReviewUiState> = flow { emit(load()) }
        .flowOn(dispatcher)
        // Lazily, not while subscribed: back from the background the story is the same one (and in the same place).
        .stateIn(viewModelScope, SharingStarted.Lazily, YearReviewUiState(year))

    private var story: StoryState? = null

    /**
     * The story's place (slide, its time, the entrances played) lives here, not in saved UI state: turning the device
     * switches the frame between the tab bar and the navigation rail, which composes the navigation host along another
     * path – its pages' remembered state does not come along (seen on the emulator), the view model does.
     */
    fun story(count: Int): StoryState = story?.takeIf { it.count == count } ?: StoryState(count).also { story = it }

    private suspend fun load(): YearReviewUiState {
        val sid = accounts.account.first()?.serverId ?: return YearReviewUiState(year, loading = false)
        val data = source.observe(sid, calendar.zones()).first()
        val incomplete = historyLoad(sid).first() != HistoryLoad.NONE
        val review = YearReview.build(year, data.sessions, data.finished, data.seriesGroups, data.achievements(), data.items, data.zone, incomplete)
        val ids = review?.slides.orEmpty().flatMapTo(LinkedHashSet()) { it.coverIds() }
        return YearReviewUiState(
            year, loading = false, review = review,
            covers = ids.associateWith { id -> ReviewCover(covers.pathFor(sid, id), data.items[id]?.coverUpdatedAt ?: 0) },
        )
    }
}

/** The titles whose covers a slide shows. */
internal fun ReviewSlide.coverIds(): List<String> = when (this) {
    is ReviewSlide.TopTitles -> titles.map { it.itemId }
    is ReviewSlide.TopPeople -> listOfNotNull(authorItemId)
    is ReviewSlide.Finished -> listOf(first.itemId, last.itemId)
    is ReviewSlide.Closing -> listOfNotNull(topTitle?.itemId)
    else -> emptyList()
}
