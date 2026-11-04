package io.wolftown.lauschig.ui.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.stats.ListeningStats
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.stats.StatsSessions
import io.wolftown.lauschig.stats.YearReview
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the Start card offers: [year] to review, or none; [known]: the data was read (before, nothing is decided). */
@Immutable
data class YearReviewOffer(val year: Int? = null, val known: Boolean = false)

/**
 * The Start screen's year-in-review card: from 1 December to 31 January the year that is ending or has just ended
 * ([YearReview.offeredYear]) – once it has an hour of listening, until the user hides it for that year. Hidden is
 * stored per account and year (`year_review_hidden_<year>:<serverId>`, a sync stamp: it goes with the account's data at
 * a sign-out without keeping). "Today" is read again at midnight, on a time or zone change and on resume ([onResume]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YearReviewCardViewModel(
    private val accounts: AccountStore,
    private val sessions: StatsSessions,
    private val calendar: StatsCalendar,
    private val stamps: SyncStamps,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, sessions: StatsSessions, calendar: StatsCalendar, stamps: SyncStamps) :
        this(accounts, sessions, calendar, stamps, System::currentTimeMillis, Dispatchers.Default)

    private val resumes = MutableStateFlow(0)

    /** Hidden in this session, before the stamp is written (the card goes at once). */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    private val serverId = accounts.account.map { it?.serverId }.distinctUntilChanged()
    private val now = merge(resumes, calendar.changes).map { calendar.now() }.distinctUntilChanged()

    /** The year to offer, or none (outside December and January, below an hour, hidden). */
    val card: StateFlow<YearReviewOffer> = combine(serverId, now, ::Pair)
        .flatMapLatest { (sid, cal) ->
            val year = if (sid == null) null else YearReview.offeredYear(cal.today)
            if (sid == null || year == null) return@flatMapLatest flowOf(NONE)
            val key = hiddenKey(year, sid)
            flow { emit(stamps.get(key) != null) }.flatMapLatest { stored ->
                if (stored) {
                    flowOf(NONE)
                } else {
                    // The rows since that year began (in January also this year's few, which the total leaves out).
                    val from = LocalDate.of(year, 1, 1).atStartOfDay(cal.zone).toInstant().toEpochMilli()
                    val total = sessions.observeRowsSince(sid, from).conflate().map { rows ->
                        YearReview.yearTotal(ListeningStats.days(ListeningStats.merge(rows.server, rows.local), cal.zone), year)
                    }
                    combine(total, hidden) { sec, gone -> YearReviewOffer(year.takeIf { sec >= YearReview.MIN_YEAR_SEC && key !in gone }, known = true) }
                }
            }
        }
        .flowOn(dispatcher)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YearReviewOffer())

    /** „Ausblenden“: not again for [year] (for this account). */
    fun hide(year: Int) {
        viewModelScope.launch {
            val sid = accounts.account.first()?.serverId ?: return@launch
            val key = hiddenKey(year, sid)
            hidden.update { it + key }
            stamps.set(key, clock())
        }
    }

    /** Start is shown again: "today" may have moved on (into December, out of January). */
    fun onResume() = resumes.update { it + 1 }

    companion object {
        fun hiddenKey(year: Int, serverId: String) = "year_review_hidden_$year:$serverId"

        private val NONE = YearReviewOffer(null, known = true)
    }
}
