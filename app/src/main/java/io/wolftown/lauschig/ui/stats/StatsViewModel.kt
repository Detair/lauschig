package io.wolftown.lauschig.ui.stats

import android.util.Log
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
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.LibraryKind
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.stats.AuthorTime
import io.wolftown.lauschig.stats.FinishedBook
import io.wolftown.lauschig.stats.FinishedSeries
import io.wolftown.lauschig.stats.ListeningStats
import io.wolftown.lauschig.stats.PeriodRange
import io.wolftown.lauschig.stats.PeriodSummary
import io.wolftown.lauschig.stats.StatSession
import io.wolftown.lauschig.stats.StatsPeriod
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.stats.Streaks
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.ui.common.CoverPathCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
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

@Immutable
data class StatsUiState(
    /** The first data from Room is not there yet. */
    val loading: Boolean = true,
    /** Asking the server on open, or still waiting for the connection check to answer. */
    val refreshing: Boolean = false,
    /** The last attempt to ask the server on open did not get through (offline, or it failed); the cache is shown. */
    val unreached: Boolean = false,
    /** Any listening at all; otherwise the page is its empty state. */
    val hasData: Boolean = false,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val summary: PeriodSummary? = null,
    /** The shown period holds today. */
    val isCurrent: Boolean = true,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val streaks: Streaks = Streaks(0, 0),
    val finished: List<StatsTitle> = emptyList(),
    val topTitles: List<StatsTitle> = emptyList(),
    val topAuthors: List<AuthorTime> = emptyList(),
    /** Only in [StatsPeriod.ALL] (a series has no date). */
    val finishedSeries: List<FinishedSeries> = emptyList(),
)

/**
 * The statistics page: everything from Room (offline-first, see [ListeningStats]); on open it asks the server once for
 * new sessions, after the connection check said online. Opens on the current week; a period switch starts at that
 * period's current one; previous/next never go into the future nor before the first listening.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel(
    accounts: AccountStore,
    private val sessions: StatsSessions,
    private val progress: ProgressDao,
    private val catalogDao: CatalogDao,
    private val libraries: LibraryDao,
    private val covers: CoverPathCache,
    private val catalog: Catalog,
    private val refresh: suspend (serverId: String) -> Unit,
    private val awaitOnline: suspend () -> Boolean,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val weekStart: () -> DayOfWeek,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(
        accounts: AccountStore, sessions: StatsSessions, progress: ProgressDao, catalogDao: CatalogDao, libraries: LibraryDao,
        covers: CoverPathCache, catalog: Catalog, sync: ListeningSessionSync, apiProvider: ApiProvider,
    ) : this(
        accounts, sessions, progress, catalogDao, libraries, covers, catalog,
        refresh = sync::sync,
        awaitOnline = { awaitConnectionAnswer(apiProvider.connection, apiProvider.checking, CONNECTION_ANSWER_WAIT_MS) },
        clock = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        weekStart = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
        dispatcher = Dispatchers.Default,
    )

    /** [anchor] null: the period that holds today. */
    private data class Selection(val period: StatsPeriod = StatsPeriod.WEEK, val anchor: LocalDate? = null)

    /** Everything that does not depend on the shown period, recomputed when the data changes. */
    private class Data(
        val serverId: String,
        val sessions: List<StatSession>,
        val firstDay: LocalDate?,
        val days: Map<LocalDate, Double>,
        val finished: List<FinishedBook>,
        val series: List<FinishedSeries>,
        val items: Map<String, ItemEntity>,
    )

    private val selection = MutableStateFlow(Selection())
    private val refreshState = MutableStateFlow(Refresh(running = true, unreached = false))

    private data class Refresh(val running: Boolean, val unreached: Boolean)

    /** The first day with a session, for the bound of [previous]; set whenever the data is computed. */
    @Volatile private var firstDay: LocalDate? = null

    private val data: Flow<Data?> = accounts.account.map { it?.serverId }.distinctUntilChanged()
        .flatMapLatest { sid -> if (sid == null) flowOf(null) else dataFor(sid) }

    private fun dataFor(sid: String): Flow<Data> {
        // The catalog (names, series) changes rarely; sessions and progress change while a title plays.
        val catalogView = combine(catalogDao.observeAll(sid), libraries.observeAll(sid)) { items, libs ->
            val podcastLibs = libs.filter { it.effectiveKind == LibraryKind.PODCAST }.mapTo(HashSet()) { it.id }
            items.associateBy { it.id } to catalog.seriesGroups(items.filter { it.libraryId !in podcastLibs })
        }
        return combine(sessions.observeNamed(sid, catalogView) { it.first }, progress.observeAll(sid)) { (merged, view), prog ->
            val (byId, groups) = view
            val z = zone()
            val days = ListeningStats.days(merged, z)
            val finished = ListeningStats.finishedBooks(merged, prog, z, byId)
            Data(
                sid, merged, days.keys.firstOrNull(), days, finished,
                ListeningStats.finishedSeries(groups, finished.mapTo(HashSet()) { it.itemId }), byId,
            ).also { firstDay = it.firstDay }
        }
    }

    val state: StateFlow<StatsUiState> = combine(data, selection, refreshState) { d, sel, r -> build(d, sel, r) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(refreshing = true))

    init {
        viewModelScope.launch {
            var reached = false
            try {
                val sid = accounts.account.first()?.serverId
                if (sid != null && awaitOnline()) {
                    refresh(sid)
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
    }

    fun setPeriod(period: StatsPeriod) = selection.update { if (it.period == period) it else Selection(period) }

    fun previous() = selection.update { sel ->
        val range = rangeOf(sel, today())
        if (ListeningStats.hasPrevious(range, firstDay)) sel.copy(anchor = ListeningStats.previousAnchor(range)) else sel
    }

    fun next() = selection.update { sel ->
        val today = today()
        val range = rangeOf(sel, today)
        if (!ListeningStats.hasNext(range, today)) return@update sel
        val anchor = ListeningStats.nextAnchor(range)
        // Back in the period that holds today: follow today again (also across midnight).
        sel.copy(anchor = if (rangeOf(sel.copy(anchor = anchor), today).end.isAfter(today)) null else anchor)
    }

    private fun today(): LocalDate = ListeningStats.dayOf(clock(), zone())

    private fun rangeOf(sel: Selection, today: LocalDate, first: LocalDate? = firstDay): PeriodRange =
        ListeningStats.range(sel.period, sel.anchor ?: today, weekStart(), first)

    private fun build(d: Data?, sel: Selection, r: Refresh): StatsUiState {
        val base = StatsUiState(loading = false, refreshing = r.running, unreached = r.unreached, period = sel.period)
        if (d == null || d.sessions.isEmpty()) return base
        val today = today()
        val range = rangeOf(sel, today, d.firstDay)
        val summary = ListeningStats.summary(d.sessions, emptyList(), range, today, zone(), finished = d.finished)
        fun row(itemId: String, title: String?, author: String?, seconds: Double? = null, day: LocalDate? = null): StatsTitle {
            val item = d.items[itemId]
            return StatsTitle(
                itemId, item?.title ?: title, item?.author ?: author, seconds, day,
                coverPath = if (item != null) covers.pathFor(d.serverId, itemId) else null,
                coverVersion = item?.coverUpdatedAt ?: 0, known = item != null,
            )
        }
        return base.copy(
            hasData = true,
            summary = summary,
            isCurrent = !today.isBefore(range.start) && today.isBefore(range.end),
            hasPrevious = ListeningStats.hasPrevious(range, d.firstDay),
            hasNext = ListeningStats.hasNext(range, today),
            streaks = ListeningStats.streaks(d.days, today),
            finished = summary.finishedBooks.map { row(it.itemId, it.title, it.author, day = it.day) },
            topTitles = summary.topTitles.map { row(it.itemId, it.title, it.author, seconds = it.seconds) },
            topAuthors = summary.topAuthors,
            finishedSeries = if (sel.period == StatsPeriod.ALL) d.series else emptyList(),
        )
    }

    private companion object {
        const val TAG = "StatsViewModel"
    }
}
