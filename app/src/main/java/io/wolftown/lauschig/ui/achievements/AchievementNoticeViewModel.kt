package io.wolftown.lauschig.ui.achievements

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.AchievementSeenDao
import io.wolftown.lauschig.db.entities.AchievementSeenEntity
import io.wolftown.lauschig.stats.AchievementKey
import io.wolftown.lauschig.stats.AchievementState
import io.wolftown.lauschig.stats.ListeningData
import io.wolftown.lauschig.stats.AchievementNotices
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.NoticeStep
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.stats.zones
import io.wolftown.lauschig.sync.HistoryLoad
import io.wolftown.lauschig.sync.ListeningSessionSync
import io.wolftown.lauschig.sync.ProgressSync
import io.wolftown.lauschig.sync.SyncStamps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Each value of the flow mapped by [transform] – computed again only when [key] differs from the last computed one's;
 * otherwise the last result is emitted again (every value still goes downstream). A null result (a failure) is not
 * remembered, so the next value computes again.
 */
internal fun <T, K, R : Any> Flow<T>.mapWhenChanged(key: (T) -> K, transform: suspend (T) -> R?): Flow<R?> = flow {
    var last: Pair<K, R>? = null
    collect { value ->
        val k = key(value)
        val known = last?.takeIf { it.first == k }
        if (known != null) {
            emit(known.second)
        } else {
            val result = transform(value)
            if (result != null) last = k to result
            emit(result)
        }
    }
}

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
    /** The expensive step (a seam for tests that count it). */
    private val evaluate: (ListeningData) -> List<AchievementState> = { it.achievements() },
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
    private val zone = calendar.zones()

    val notice: StateFlow<AchievementNotice?> = serverId.flatMapLatest { sid -> if (sid == null) flowOf(null) else noticeFor(sid) }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Every data change is a new look (the progress sync that settles the history may change no tier), but the history
     * is evaluated again only when something a tier depends on moved ([ListeningData.tierInputs]): a playback tick on a
     * day that counts already is not. A failure (the evaluation, a read, a write) tells nothing and never reaches the
     * screen; a failed step is tried again with the next change.
     */
    private fun noticeFor(sid: String): Flow<AchievementNotice?> {
        val reached = source.observe(sid, zone).mapWhenChanged({ it.tierInputs }) { data -> reachedKeys(data) }
        return combine(reached, seen.observeAll(sid), historyLoad(sid), ::Triple)
            .map { (keys, rows, history) -> step(sid, keys, rows, history) }
            .distinctUntilChanged()
            .catch { e ->
                Log.w(TAG, "achievement notice stopped", e)
                emit(null)
            }
    }

    /** The reached tiers, or null when the evaluation failed. */
    private fun reachedKeys(data: ListeningData): List<AchievementKey>? = try {
        evaluate(data).flatMap { it.keys }
    } catch (e: Exception) {
        Log.w(TAG, "achievements not evaluated", e)
        null
    }

    private suspend fun step(sid: String, keys: List<AchievementKey>?, rows: List<AchievementSeenEntity>, history: HistoryLoad): AchievementNotice? {
        if (keys == null) return null
        return try {
            val hasBaseline = rows.any { it.id == AchievementSeenEntity.BASELINE_ID }
            val settled = !hasBaseline && history == HistoryLoad.NONE && progressSynced(sid)
            val told = rows.mapTo(HashSet()) { AchievementKey(it.id, it.tier) }
            when (val step = AchievementNotices.step(keys, told, hasBaseline, settled)) {
                NoticeStep.Wait -> null
                is NoticeStep.Baseline -> {
                    // Not from [keys]: the history's last page may still be in evaluation when its "complete" arrives
                    // (the sync flips it right after writing). That page has committed by now, so a fresh look holds it.
                    val fresh = reachedKeys(source.observe(sid, zone).first()) ?: return null
                    val at = clock()
                    seen.insertAll(fresh.map { AchievementSeenEntity(sid, it.id, it.tier, at) } + AchievementSeenEntity(sid, AchievementSeenEntity.BASELINE_ID, 0, at))
                    null
                }
                is NoticeStep.Announce -> step.new.takeIf { it.isNotEmpty() }?.let { AchievementNotice(sid, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "achievement notice step failed", e)
            null
        }
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

    private companion object {
        const val TAG = "AchievementNotice"
    }
}
