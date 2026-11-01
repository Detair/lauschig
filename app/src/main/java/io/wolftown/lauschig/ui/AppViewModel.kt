package io.wolftown.lauschig.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.sync.PositionJumpBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    api: ApiProvider,
    accountStore: AccountStore,
    val jumpBus: PositionJumpBus,
    val player: PlayerController,
) : ViewModel() {
    val connection: StateFlow<ConnectionState> =
        api.connection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Offline)

    val serverId: StateFlow<String?> =
        accountStore.account.map { it?.serverId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A refresh was rejected: show the "sign in again" hint. Never logs out; local data stays usable. */
    val needsReauth: StateFlow<Boolean> =
        combine(accountStore.account, accountStore.needsReauth) { account, flag -> account != null && flag }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
