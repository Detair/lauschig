package io.wolftown.lauschig.rmab

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.external.AudibleVolume
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [connected]: a usable token exists (a rejected one counts as not connected). */
@Immutable
data class RmabUiState(
    val connected: Boolean = false,
    val statuses: Map<String, RmabStatus> = emptyMap(),
    /** Last request result per ASIN, shown inside the sheet (a snackbar would hide behind it). */
    val outcomes: Map<String, RmabOutcome> = emptyMap(),
)

/** A text: [res] with an optional single format argument. */
data class RmabMessage(@StringRes val res: Int, val arg: String? = null)

data class RmabOutcome(val message: RmabMessage, val isError: Boolean)

/** Status and requests for the missing-volume sheet. */
@HiltViewModel
class RmabViewModel @Inject constructor(private val repo: RmabRepository) : ViewModel() {
    private val statuses = MutableStateFlow<Map<String, RmabStatus>>(emptyMap())
    private val outcomes = MutableStateFlow<Map<String, RmabOutcome>>(emptyMap())
    /** Optimistic entries (asin -> since) the server list may not know yet; dropped once reported or after [OPTIMISTIC_MS]. */
    private val optimistic = HashMap<String, Long>()
    private val connected: Flow<Boolean> = repo.config.map { it != null && !it.needsReconnect }.distinctUntilChanged()

    val state: StateFlow<RmabUiState> = combine(connected, statuses, outcomes, ::RmabUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RmabUiState())

    init {
        viewModelScope.launch {
            connected.collect { on -> if (on) refresh() else statuses.value = emptyMap() }
        }
    }

    private suspend fun refresh() {
        // An empty answer is also what a failed lookup returns; keep what we know instead of blanking the tiles.
        val fresh = repo.statusByAsin().takeIf { it.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        optimistic.entries.removeAll { (asin, since) -> asin in fresh || now - since > OPTIMISTIC_MS }
        statuses.update { old -> fresh + old.filterKeys { it in optimistic } }
    }

    fun request(volume: AudibleVolume) {
        viewModelScope.launch {
            outcomes.update { it - volume.asin }
            val r = repo.request(volume)
            val (message, error) = when (r) {
                RmabRequestResult.Created -> RmabMessage(R.string.rmab_msg_created, volume.title) to false
                RmabRequestResult.AlreadyAvailable -> RmabMessage(R.string.rmab_msg_already_available) to false
                RmabRequestResult.AlreadyRequested -> RmabMessage(R.string.rmab_msg_already_requested) to false
                RmabRequestResult.BeingProcessed -> RmabMessage(R.string.rmab_msg_being_processed) to false
                RmabRequestResult.NoAuthor -> RmabMessage(R.string.rmab_msg_no_author) to true
                RmabRequestResult.NotConnected -> RmabMessage(R.string.rmab_msg_not_connected) to true
                RmabRequestResult.Unreachable -> RmabMessage(R.string.rmab_msg_unreachable) to true
                is RmabRequestResult.Failed -> RmabMessage(R.string.rmab_msg_failed, r.message) to true
            }
            outcomes.update { it + (volume.asin to RmabOutcome(message, error)) }
            when (r) {
                RmabRequestResult.Created, RmabRequestResult.AlreadyRequested, RmabRequestResult.BeingProcessed -> {
                    // Show it right away; refresh() keeps it until the server's list reports the ASIN.
                    optimistic[volume.asin] = System.currentTimeMillis()
                    statuses.update { it + (volume.asin to (it[volume.asin]?.takeIf { s -> s != RmabStatus.FAILED } ?: RmabStatus.WAITING)) }
                    refresh()
                }
                RmabRequestResult.AlreadyAvailable -> {
                    optimistic[volume.asin] = System.currentTimeMillis()
                    statuses.update { it + (volume.asin to RmabStatus.DONE) }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val OPTIMISTIC_MS = 60_000L
    }
}

enum class RmabConnectError(@StringRes val message: Int) {
    WRONG_LOGIN(R.string.rmab_error_wrong_login),
    PENDING(R.string.rmab_error_pending),
    LOCAL_DISABLED(R.string.rmab_error_local_disabled),
    UNREACHABLE(R.string.rmab_error_unreachable),
    INVALID_URL(R.string.rmab_error_invalid_url),
    TOKEN_CREATION(R.string.rmab_error_token_creation),
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
                    RmabConnectResult.TokenCreationFailed -> it.copy(loading = false, error = RmabConnectError.TOKEN_CREATION)
                    RmabConnectResult.InvalidUrl -> it.copy(loading = false, error = RmabConnectError.INVALID_URL)
                }
            }
        }
    }
}
