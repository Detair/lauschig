package io.wolftown.lauschig.ui.achievements

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.stats.AchievementState
import io.wolftown.lauschig.stats.Achievements
import io.wolftown.lauschig.stats.ListeningDataSource
import io.wolftown.lauschig.stats.StatsCalendar
import io.wolftown.lauschig.stats.zones
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class AchievementsUiState(
    /** The first data from Room is not there yet. */
    val loading: Boolean = true,
    /** Every achievement, recently unlocked first, then the closest to their next tier ([Achievements.sorted]). */
    val cards: List<AchievementState> = emptyList(),
    val unlockedTiers: Int = 0,
    val totalTiers: Int = Achievements.TOTAL_TIERS,
)

/** The achievements page: computed from Room (offline-first) in the device zone, again whenever the data changes. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AchievementsViewModel(
    accounts: AccountStore,
    source: ListeningDataSource,
    calendar: StatsCalendar,
    dispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, source: ListeningDataSource, calendar: StatsCalendar) :
        this(accounts, source, calendar, Dispatchers.Default)

    val state: StateFlow<AchievementsUiState> = accounts.account.map { it?.serverId }.distinctUntilChanged()
        .flatMapLatest { sid ->
            if (sid == null) {
                flowOf(ui(Achievements.evaluate(emptyList(), emptyList(), emptyList(), emptyMap(), calendar.now().zone)))
            } else {
                source.observe(sid, calendar.zones()).map { ui(it.achievements()) }
            }
        }
        .flowOn(dispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsUiState())

    private fun ui(states: List<AchievementState>) =
        AchievementsUiState(loading = false, cards = Achievements.sorted(states), unlockedTiers = Achievements.unlockedTiers(states))
}
