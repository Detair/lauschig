package io.wolftown.lauschig.rmab

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.external.AudibleVolume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [connected]: a usable token exists (a rejected one counts as not connected). */
@Immutable
data class RmabUiState(val connected: Boolean = false, val statuses: Map<String, RmabStatus> = emptyMap())

/** A snackbar text: [res] with an optional single format argument. */
data class RmabMessage(@StringRes val res: Int, val arg: String? = null)

/** Status and requests for the missing-volume sheet. */
@HiltViewModel
class RmabViewModel @Inject constructor(private val repo: RmabRepository) : ViewModel() {
    private val statuses = MutableStateFlow<Map<String, RmabStatus>>(emptyMap())
    private val connected: Flow<Boolean> = repo.config.map { it != null && !it.needsReconnect }.distinctUntilChanged()

    val state: StateFlow<RmabUiState> = combine(connected, statuses, ::RmabUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RmabUiState())

    private val _messages = Channel<RmabMessage>(Channel.BUFFERED)
    val messages: Flow<RmabMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            connected.collect { on -> if (on) refresh() else statuses.value = emptyMap() }
        }
    }

    private suspend fun refresh() {
        // An empty answer is also what a failed lookup returns; keep what we know instead of blanking the tiles.
        repo.statusByAsin().takeIf { it.isNotEmpty() }?.let { statuses.value = it }
    }

    fun request(volume: AudibleVolume) {
        viewModelScope.launch {
            val r = repo.request(volume)
            _messages.send(
                when (r) {
                    RmabRequestResult.Created -> RmabMessage(R.string.rmab_msg_created, volume.title)
                    RmabRequestResult.AlreadyAvailable -> RmabMessage(R.string.rmab_msg_already_available)
                    RmabRequestResult.AlreadyRequested -> RmabMessage(R.string.rmab_msg_already_requested)
                    RmabRequestResult.BeingProcessed -> RmabMessage(R.string.rmab_msg_being_processed)
                    RmabRequestResult.NoAuthor -> RmabMessage(R.string.rmab_msg_no_author)
                    RmabRequestResult.NotConnected -> RmabMessage(R.string.rmab_msg_not_connected)
                    RmabRequestResult.Unreachable -> RmabMessage(R.string.rmab_msg_unreachable)
                    is RmabRequestResult.Failed -> RmabMessage(R.string.rmab_msg_failed, r.message)
                },
            )
            when (r) {
                RmabRequestResult.Created, RmabRequestResult.AlreadyRequested, RmabRequestResult.BeingProcessed -> {
                    // Show it right away; the refresh below replaces it with the server's word.
                    statuses.update { it + (volume.asin to (it[volume.asin]?.takeIf { s -> s != RmabStatus.FAILED } ?: RmabStatus.WAITING)) }
                    refresh()
                }
                RmabRequestResult.AlreadyAvailable -> {
                    statuses.update { it + (volume.asin to RmabStatus.DONE) }
                }
                else -> Unit
            }
        }
    }
}

enum class RmabConnectError(@StringRes val message: Int) {
    WRONG_LOGIN(R.string.rmab_error_wrong_login),
    PENDING(R.string.rmab_error_pending),
    LOCAL_DISABLED(R.string.rmab_error_local_disabled),
    UNREACHABLE(R.string.rmab_error_unreachable),
    INVALID_URL(R.string.rmab_error_invalid_url),
}

@Immutable
data class RmabSettingsState(
    val config: RmabConfig? = null,
    val dialogOpen: Boolean = false,
    val loading: Boolean = false,
    val error: RmabConnectError? = null,
    /** Host awaiting the user's OK to talk plain HTTP. */
    val cleartextHost: String? = null,
)

/** The settings section: connect dialog and disconnect. */
@HiltViewModel
class RmabSettingsViewModel @Inject constructor(private val repo: RmabRepository) : ViewModel() {
    private class Attempt(val url: String, val user: String, val secret: String, val tokenMode: Boolean)

    private val ui = MutableStateFlow(RmabSettingsState())
    private var pending: Attempt? = null

    val state: StateFlow<RmabSettingsState> = combine(repo.config, ui) { c, u -> u.copy(config = c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RmabSettingsState())

    fun openDialog() = ui.update { RmabSettingsState(dialogOpen = true) }

    fun closeDialog() {
        pending = null
        ui.value = RmabSettingsState()
    }

    fun connect(url: String, user: String, secret: String, tokenMode: Boolean) = attempt(Attempt(url, user, secret, tokenMode), false)

    fun confirmCleartext() {
        pending?.let { attempt(it, true) }
    }

    fun dismissCleartext() {
        pending = null
        ui.update { it.copy(cleartextHost = null) }
    }

    fun disconnect() {
        viewModelScope.launch { repo.disconnect() }
    }

    private fun attempt(a: Attempt, allowCleartext: Boolean) {
        ui.update { it.copy(loading = true, error = null, cleartextHost = null) }
        viewModelScope.launch {
            val r = if (a.tokenMode) repo.connectWithToken(a.url, a.secret, allowCleartext)
            else repo.connect(a.url, a.user, a.secret, allowCleartext)
            if (r is RmabConnectResult.NeedsCleartextConfirmation) pending = a else pending = null
            ui.update {
                when (r) {
                    RmabConnectResult.Ok -> RmabSettingsState()
                    is RmabConnectResult.NeedsCleartextConfirmation -> it.copy(loading = false, cleartextHost = r.host)
                    RmabConnectResult.WrongLogin -> it.copy(loading = false, error = RmabConnectError.WRONG_LOGIN)
                    RmabConnectResult.PendingApproval -> it.copy(loading = false, error = RmabConnectError.PENDING)
                    RmabConnectResult.LocalLoginDisabled -> it.copy(loading = false, error = RmabConnectError.LOCAL_DISABLED)
                    RmabConnectResult.Unreachable -> it.copy(loading = false, error = RmabConnectError.UNREACHABLE)
                    RmabConnectResult.InvalidUrl -> it.copy(loading = false, error = RmabConnectError.INVALID_URL)
                }
            }
        }
    }
}
