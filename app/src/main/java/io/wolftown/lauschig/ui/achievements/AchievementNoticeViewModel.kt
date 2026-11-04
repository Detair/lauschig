package io.wolftown.lauschig.ui.achievements

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.AchievementSeenDao
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import io.wolftown.lauschig.stats.AchievementKey
import io.wolftown.lauschig.stats.AchievementNotices
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.NoticeStep
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Tiers of [serverId]'s achievements reached and not told yet, in catalogue order. */
@Immutable
data class AchievementNotice(val serverId: String, val unlocks: List<AchievementKey>)

/**
 * The notice on Start ([AchievementNoticeSnackbar]): computed from Room while the screen listens. The first evaluation
 * of an account waits for its complete history (see [AchievementNotices]) and marks everything reached then as seen
 * silently – a first sync of years of listening tells nothing; afterwards each new unlock is told once ([shown]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AchievementNoticeViewModel(
    private val accounts: AccountStore,
    private val source: ListeningDataSource,
    private val seen: AchievementSeenDao,
    private val historyLoad: (serverId: String) -> Flow<HistoryLoad>,
    /** The progress was synced at least once (finished books the sessions do not show are known). */
    private val progressSynced: suspend (serverId: String) -> Boolean,
    private val calendar: StatsCalendar,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(
        accounts: AccountStore, source: ListeningDataSource, seen: AchievementSeenDao, sync: ListeningSessionSync, stamps: SyncStamps,
        calendar: StatsCalendar,
    ) : this(
        accounts, source, seen, sync::historyLoad, { stamps.get(ProgressSync.stampKey(it)) != null }, calendar,
        System::currentTimeMillis, Dispatchers.Default,
    )

    private val serverId = accounts.account.map { it?.serverId }.distinctUntilChanged()

    /** Time windows and days are read in the device zone. */
    private val zone = calendar.changes.map { calendar.now().zone }.onStart { emit(calendar.now().zone) }.distinctUntilChanged()

    val notice: StateFlow<AchievementNotice?> = serverId.flatMapLatest { sid -> if (sid == null) flowOf(null) else noticeFor(sid) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun noticeFor(sid: String): Flow<AchievementNotice?> {
        val reached = source.observe(sid, zone).map { data -> data.achievements().flatMap { it.keys } }.distinctUntilChanged()
        return combine(reached, seen.observeAll(sid), historyLoad(sid), ::Triple)
            .map { (keys, rows, history) ->
                val hasBaseline = rows.any { it.id == AchievementSeenEntity.BASELINE_ID }
                val settled = !hasBaseline && history == HistoryLoad.NONE && progressSynced(sid)
                val told = rows.mapTo(HashSet()) { AchievementKey(it.id, it.tier) }
                when (val step = AchievementNotices.step(keys, told, hasBaseline, settled)) {
                    NoticeStep.Wait -> null
                    is NoticeStep.Baseline -> {
                        val at = clock()
                        seen.insertAll(step.reached.map { AchievementSeenEntity(sid, it.id, it.tier, at) } + AchievementSeenEntity(sid, AchievementSeenEntity.BASELINE_ID, 0, at))
                        null
                    }
                    is NoticeStep.Announce -> step.new.takeIf { it.isNotEmpty() }?.let { AchievementNotice(sid, it) }
                }
            }
            .distinctUntilChanged()
    }

    /**
     * The snackbar of [notice] ended (dismissed, timed out or opened): its unlocks are never told again. Nothing for an
     * account that is no longer signed in (its rows are gone with it).
     */
    fun shown(notice: AchievementNotice) {
        viewModelScope.launch {
            if (accounts.account.first()?.serverId != notice.serverId) return@launch
            val at = clock()
            seen.insertAll(notice.unlocks.map { AchievementSeenEntity(notice.serverId, it.id, it.tier, at) })
        }
    }
}
