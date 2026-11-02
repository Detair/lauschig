package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.TitleAudioDao
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimer
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sleep timer and audio options of the full player. [titleBoostDb] null: the title uses [defaultBoostDb]. */
@Immutable
data class ComfortUiState(
    val sleep: SleepTimerUi? = null,
    val skipSilence: Boolean = false,
    val titleBoostDb: Int? = null,
    val defaultBoostDb: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ComfortViewModel @Inject constructor(
    private val timer: SleepTimer,
    private val settings: PlaybackSettings,
    private val titleAudio: TitleAudioDao,
    private val accounts: AccountStore,
    player: PlayerController,
) : ViewModel() {
    private val key = combine(accounts.account.map { it?.serverId }, player.state.map { it.itemId }) { s, i ->
        i?.let { id -> DeviceLibrary.serverFor(s, id)?.let { it to id } }
    }
        .distinctUntilChanged()

    private val titleBoost = key.flatMapLatest { k -> if (k == null) flowOf(null) else titleAudio.observe(k.first, k.second).map { it?.boostDb } }

    val state: StateFlow<ComfortUiState> = combine(timer.state, settings.skipSilence, titleBoost, settings.defaultBoostDb) { sleep, skip, boost, def ->
        ComfortUiState(sleep, skip, boost, def)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComfortUiState(sleep = timer.state.value))

    fun startSleep(mode: SleepMode) = timer.start(mode)
    fun cancelSleep() = timer.cancel()
    fun setSkipSilence(on: Boolean) { viewModelScope.launch { settings.setSkipSilence(on) } }

    /** null: back to the default boost. */
    fun setBoost(db: Int?) {
        viewModelScope.launch { key.first()?.let { (s, i) -> titleAudio.setBoost(s, i, db) } }
    }
}
