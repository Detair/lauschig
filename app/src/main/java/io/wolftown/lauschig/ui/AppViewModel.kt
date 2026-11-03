package io.wolftown.lauschig.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.download.DownloadPrompt
import io.wolftown.lauschig.download.DownloadPrompts
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.sync.PositionJumpBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    api: ApiProvider,
    accountStore: AccountStore,
    val jumpBus: PositionJumpBus,
    val player: PlayerController,
    prompts: DownloadPrompts,
    private val starter: PlayDownloadStarter,
) : ViewModel() {
    /** The open download question (download on play over mobile data, stream vs. download). */
    val downloadPrompt: StateFlow<DownloadPrompt?> = prompts.prompt

    fun answerMetered(prompt: DownloadPrompt.Metered, now: Boolean, remember: Boolean) {
        viewModelScope.launch { starter.answerMetered(prompt, now, remember) }
    }

    /** [download]: playback of that title pauses so the download gets the whole connection. */
    fun answerContention(prompt: DownloadPrompt.Contention, download: Boolean) {
        if (download && player.state.value.itemId == prompt.itemId) player.pause()
        viewModelScope.launch { starter.answerContention(prompt, download) }
    }

    fun dismissPrompt(prompt: DownloadPrompt) = starter.dismiss(prompt)

    val connection: StateFlow<ConnectionState> =
        api.connection.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Offline)

    /** The first connection check since the start is still running (the banner shows "Verbinde …", not "Offline"). */
    val checking: StateFlow<Boolean> = api.checking

    val serverId: StateFlow<String?> =
        accountStore.account.map { it?.serverId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A refresh was rejected: show the "sign in again" hint. Never logs out; local data stays usable. */
    val needsReauth: StateFlow<Boolean> =
        combine(accountStore.account, accountStore.needsReauth) { account, flag -> account != null && flag }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}
