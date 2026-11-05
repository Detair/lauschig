package io.wolftown.lauschig.ui.stats

import io.wolftown.lauschig.diagnostics.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.stats.AchievementsPreview
import io.wolftown.lauschig.stats.AuthorTime
import io.wolftown.lauschig.stats.FinishedSeries
import io.wolftown.lauschig.stats.ListeningData
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.ListeningStats
import io.wolftown.lauschig.stats.PeriodRange
import io.wolftown.lauschig.stats.PeriodSummary
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import io.wolftown.lauschig.stats.CalendarNow
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.sync.HistoryLoad
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

/** A title row of the page: a most-heard title ([seconds]) or a finished book ([day]). */
@Immutable
data class StatsTitle(
    val itemId: String,
    /** Null when neither the catalog nor the session names it. */
    val title: String?,
    val author: String?,
    val seconds: Double? = null,
    val day: LocalDate? = null,
    val coverPath: String? = null,
    val coverVersion: Long = 0,
    /** The catalog has it, so its details can be opened. */
    val known: Boolean = false,
)

/** The two streak tiles: today's streaks under the period that holds today, the period's own under a past one. */
@Immutable
sealed interface StatsStreakTiles {
    /** Days in a row up to today, and the longest run ever. */
    data class Current(val streak: Int, val record: Int) : StatsStreakTiles

    /** The longest run inside the past period, and on how many of its days something was heard. */
    data class Past(val longest: Int, val activeDays: Int) : StatsStreakTiles
}

@Immutable
data class StatsUiState(
    /** The first data from Room is not there yet. */
    val loading: Boolean = true,
    /** Asking the server on open, or still waiting for the connection check to answer. */
    val refreshing: Boolean = false,
    /** The last attempt to ask the server on open did not get through (offline, or it failed); the cache is shown. */
    val unreached: Boolean = false,
    /** The whole history is being read, or waits for Wi-Fi (the numbers may still grow). */
    val history: HistoryLoad = HistoryLoad.NONE,
    /** Any listening at all; otherwise the page is its empty state. */
    val hasData: Boolean = false,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val summary: PeriodSummary? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val tiles: StatsStreakTiles = StatsStreakTiles.Current(0, 0),
    val finished: List<StatsTitle> = emptyList(),
    val topTitles: List<StatsTitle> = emptyList(),
    val topAuthors: List<AuthorTime> = emptyList(),
    /** Only in [StatsPeriod.ALL] (a series has no date). */
    val finishedSeries: List<FinishedSeries> = emptyList(),
    /** For dates: a finished book's day names its year when it is not this one. */
    val today: LocalDate? = null,
    /** The latest achievements and the count (not tied to the period); null without data. */
    val achievements: AchievementsPreview? = null,
    /** In „Jahr“: the shown year when it has a review (an hour of listening, [YearReview.MIN_YEAR_SEC]); else null. */
    val reviewYear: Int? = null,
)

/**
 * The statistics page: everything from Room (offline-first, see [ListeningStats]); for the signed-in account it asks the
 * server once for new sessions after the connection check said online (cancelled when the stored account changes – for a
 * sign-out that is its last step, when the account is cleared). „Jetzt laden“ hands a full read to the sync, which runs
 * it beyond this page and ends it at the start of a sign-out.
 * Opens on the current week; a period switch starts at that period's current one; previous/next never go into
 * the future nor before the first listening. "Today", the zone and the week start come from [calendar] and are read
 * again at midnight, on a time, zone or language change and when the page resumes ([onResume]). A burst of database
 * writes (a playing title, a sync writing pages) is computed once, off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel(
    private val accounts: AccountStore,
    private val sessions: StatsSessions,
    private val progress: ProgressDao,
    private val catalogDao: CatalogDao,
    private val libraries: LibraryDao,
    private val covers: CoverPathCache,
    private val catalog: Catalog,
    /** Asks the server for new sessions; `allowMetered`: the user asked to read the whole history now. */
    private val refresh: suspend (serverId: String, allowMetered: Boolean) -> Unit,
    private val awaitOnline: suspend () -> Boolean,
    private val historyLoad: (serverId: String) -> Flow<HistoryLoad>,
    /** „Jetzt laden“ for an account: runs outside this page (leaving it does not stop it), see [ListeningSessionSync.loadNow]. */
    private val loadNow: (serverId: String) -> Unit,
    private val calendar: StatsCalendar,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(
        accounts: AccountStore, sessions: StatsSessions, progress: ProgressDao, catalogDao: CatalogDao, libraries: LibraryDao,
        covers: CoverPathCache, catalog: Catalog, sync: ListeningSessionSync, apiProvider: ApiProvider, calendar: StatsCalendar,
    ) : this(
        accounts, sessions, progress, catalogDao, libraries, covers, catalog,
        refresh = { sid, allowMetered -> sync.sync(sid, allowMetered) },
        awaitOnline = { awaitConnectionAnswer(apiProvider.connection, apiProvider.checking, CONNECTION_ANSWER_WAIT_MS) },
        historyLoad = sync::historyLoad,
        loadNow = { sync.loadNow(it) },
        calendar = calendar,
        dispatcher = Dispatchers.Default,
    )

    /** [anchor] null: the period that holds today. */
    private data class Selection(val period: StatsPeriod = StatsPeriod.WEEK, val anchor: LocalDate? = null)

    /** Everything that does not depend on the shown period, recomputed when the data changes. */
    private class Data(val listening: ListeningData, val achievements: AchievementsPreview) {
        val serverId get() = listening.serverId
        val sessions get() = listening.sessions
        val firstDay get() = listening.firstDay
        val days get() = listening.days
        val finished get() = listening.finished
        val series get() = listening.series
        val items get() = listening.items
    }

    private val source = ListeningDataSource(sessions, progress, catalogDao, libraries, catalog)

    private val selection = MutableStateFlow(Selection())
    private val refreshState = MutableStateFlow(Refresh(running = true, unreached = false))
    private val resumes = MutableStateFlow(0)

    private data class Refresh(val running: Boolean, val unreached: Boolean)

    private val serverId = accounts.account.map { it?.serverId }.distinctUntilChanged()

    /** "Today" as of the last midnight, time/zone/language change or resume – one source for the whole page. */
    private val now: StateFlow<CalendarNow> = merge(resumes, calendar.changes).map { calendar.now() }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), calendar.now())


    /**
     * The first day with a session, for the bound of [previous]. Written off the main thread whenever the data is
     * computed; read by [previous] on the main thread, so it may lag one emission (the state's own bound does not).
     */
    @Volatile private var firstDay: LocalDate? = null

    private val data: Flow<Data?> = serverId.flatMapLatest { sid -> if (sid == null) flowOf(null) else dataFor(sid) }

    /** The shared Room pipeline (a burst of writes is computed once), plus the achievements, which no period changes. */
    private fun dataFor(sid: String): Flow<Data> =
        source.observe(sid, now.map { it.zone }).map { d -> Data(d, AchievementsPreview.of(d.achievements())).also { firstDay = d.firstDay } }

    private val history: Flow<HistoryLoad> = serverId.flatMapLatest { sid -> if (sid == null) flowOf(HistoryLoad.NONE) else historyLoad(sid) }

    val state: StateFlow<StatsUiState> = combine(data, selection, refreshState, now, history) { d, sel, r, cal, h -> build(d, sel, r, cal, h) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(refreshing = true))

    init {
        // One refresh per signed-in account; a sign-out or another account cancels a refresh still running.
        viewModelScope.launch {
            serverId.collectLatest { sid ->
                if (sid == null) {
                    refreshState.value = Refresh(running = false, unreached = false)
                    return@collectLatest
                }
                refreshOnce(sid, allowMetered = false)
            }
        }
    }

    private suspend fun refreshOnce(sid: String, allowMetered: Boolean) {
        refreshState.value = Refresh(running = true, unreached = false)
        var reached = false
        try {
            if (awaitOnline()) {
                refresh(sid, allowMetered)
                reached = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listening sessions not refreshed", e)
        } finally {
            refreshState.value = Refresh(running = false, unreached = !reached)
        }
    }

    /** „Jetzt laden“: read the whole history now, also over mobile data (one tap, one run, never dropped). */
    fun loadHistoryNow() {
        viewModelScope.launch { accounts.account.first()?.serverId?.let(loadNow) }
    }

    /** The page is shown again: "today" may have moved on while it was away. */
    fun onResume() = resumes.update { it + 1 }

    fun setPeriod(period: StatsPeriod) = selection.update { if (it.period == period) it else Selection(period) }

    fun previous() = selection.update { sel ->
        val range = rangeOf(sel, calendar.now())
        if (ListeningStats.hasPrevious(range, firstDay)) sel.copy(anchor = ListeningStats.previousAnchor(range)) else sel
    }

    fun next() = selection.update { sel ->
        val cal = calendar.now()
        val range = rangeOf(sel, cal)
        if (!ListeningStats.hasNext(range, cal.today)) return@update sel
        val anchor = ListeningStats.nextAnchor(range)
        // Back in the period that holds today: follow today again (also across midnight).
        sel.copy(anchor = if (rangeOf(sel.copy(anchor = anchor), cal).end.isAfter(cal.today)) null else anchor)
    }

    private fun rangeOf(sel: Selection, cal: CalendarNow, first: LocalDate? = firstDay): PeriodRange =
        ListeningStats.range(sel.period, sel.anchor ?: cal.today, cal.weekStart, first)

    private fun build(d: Data?, sel: Selection, r: Refresh, cal: CalendarNow, history: HistoryLoad): StatsUiState {
        val base = StatsUiState(
            loading = false, refreshing = r.running, unreached = r.unreached, history = history, period = sel.period, today = cal.today,
        )
        if (d == null || d.sessions.isEmpty()) return base
        val today = cal.today
        val range = rangeOf(sel, cal, d.firstDay)
        val summary = ListeningStats.summary(d.sessions, emptyList(), range, today, cal.zone, finished = d.finished)
        fun row(itemId: String, title: String?, author: String?, seconds: Double? = null, day: LocalDate? = null): StatsTitle {
            val item = d.items[itemId]
            return StatsTitle(
                itemId, item?.title ?: title, item?.author ?: author, seconds, day,
                coverPath = if (item != null) covers.pathFor(d.serverId, itemId) else null,
                coverVersion = item?.coverUpdatedAt ?: 0, known = item != null,
            )
        }
        val holdsToday = !today.isBefore(range.start) && today.isBefore(range.end)
        val tiles = if (holdsToday) {
            ListeningStats.streaks(d.days, today).let { StatsStreakTiles.Current(it.current, it.longest) }
        } else {
            StatsStreakTiles.Past(summary.longestStreak, summary.activeDays)
        }
        return base.copy(
            hasData = true,
            summary = summary,
            hasPrevious = ListeningStats.hasPrevious(range, d.firstDay),
            hasNext = ListeningStats.hasNext(range, today),
            tiles = tiles,
            finished = summary.finishedBooks.map { row(it.itemId, it.title, it.author, day = it.day) },
            topTitles = summary.topTitles.map { row(it.itemId, it.title, it.author, seconds = it.seconds) },
            topAuthors = summary.topAuthors,
            finishedSeries = if (sel.period == StatsPeriod.ALL) d.series else emptyList(),
            achievements = d.achievements,
            reviewYear = range.start.year.takeIf { sel.period == StatsPeriod.YEAR && summary.totalSec >= YearReview.MIN_YEAR_SEC },
        )
    }

    private companion object {
        const val TAG = "StatsViewModel"
    }
}
