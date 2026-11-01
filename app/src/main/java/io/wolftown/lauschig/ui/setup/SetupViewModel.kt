package io.wolftown.lauschig.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import javax.inject.Inject

enum class SetupError { INVALID_ADDRESS, UNREACHABLE, WRONG_LOGIN, SERVER_TOO_OLD, SAVE_FAILED }

data class SetupUiState(
    val local: String = "",
    val public: String = "",
    val username: String = "",
    val password: String = "",
    val ssidBinding: Boolean = false,
    val currentSsid: String? = null,
    /** Home SSIDs of the existing account (re-auth), kept while the binding stays on. */
    val keptSsids: Set<String> = emptySet(),
    /** The SSID could not be read (permission denied or no Wi-Fi), so the binding stays off. */
    val ssidHint: Boolean = false,
    val loading: Boolean = false,
    val error: SetupError? = null,
    /** Plain-HTTP servers awaiting the user's confirmation (`http://host:port`), or null. */
    val cleartextPrompt: List<String>? = null,
    val done: Boolean = false,
) {
    val canLogin: Boolean
        get() = !loading && (local.isNotBlank() || public.isNotBlank()) && username.isNotBlank() && password.isNotEmpty()
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val accounts: AccountStore,
    private val loginClient: LoginClient,
    private val network: NetworkInfo,
    private val sync: SyncTrigger,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private val confirmed = mutableSetOf<String>()

    init {
        // Re-auth: pre-fill everything but the password from the current account (read locally, no network).
        viewModelScope.launch {
            val a = accounts.account.first() ?: return@launch
            confirmed += a.allowCleartext
            val ssids = a.addresses.homeSsids
            _state.update {
                if (it.local.isNotEmpty() || it.public.isNotEmpty() || it.username.isNotEmpty()) it
                else it.copy(
                    local = a.addresses.local?.toString()?.trimEnd('/').orEmpty(),
                    public = a.addresses.public?.toString()?.trimEnd('/').orEmpty(),
                    username = a.username,
                    ssidBinding = ssids.isNotEmpty(),
                    currentSsid = ssids.firstOrNull(),
                    keptSsids = ssids,
                )
            }
        }
    }

    fun onLocalChanged(v: String) = _state.update { it.copy(local = v, error = null) }
    fun onPublicChanged(v: String) = _state.update { it.copy(public = v, error = null) }
    fun onUsernameChanged(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPasswordChanged(v: String) = _state.update { it.copy(password = v, error = null) }

    fun disableSsidBinding() = _state.update { it.copy(ssidBinding = false, ssidHint = false, keptSsids = emptySet()) }

    /** Called once the location permission is granted: pre-fills the current SSID, or shows a hint and stays off. */
    fun enableSsidBinding() {
        viewModelScope.launch {
            val ssid = network.currentSsid()
            _state.update {
                if (ssid == null) it.copy(ssidBinding = false, currentSsid = null, ssidHint = true)
                else it.copy(ssidBinding = true, currentSsid = ssid, ssidHint = false)
            }
        }
    }

    fun ssidPermissionDenied() = _state.update { it.copy(ssidBinding = false, ssidHint = true) }

    fun login() {
        val s = _state.value
        if (!s.canLogin) return
        val local = parse(s.local, "http")
        val public = parse(s.public, "https")
        if ((s.local.isNotBlank() && local == null) || (s.public.isNotBlank() && public == null)) {
            _state.update { it.copy(error = SetupError.INVALID_ADDRESS) }
            return
        }
        val urls = listOfNotNull(local, public)
        val unconfirmed = urls.filter { !it.isHttps && it.cleartextKey() !in confirmed }.map { it.cleartextKey() }.distinct()
        if (unconfirmed.isNotEmpty()) {
            _state.update { it.copy(cleartextPrompt = unconfirmed) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch { attempt(s, local, public) }
    }

    fun confirmCleartext() {
        val prompt = _state.value.cleartextPrompt ?: return
        confirmed += prompt
        _state.update { it.copy(cleartextPrompt = null) }
        login()
    }

    fun dismissCleartext() = _state.update { it.copy(cleartextPrompt = null) }

    private suspend fun attempt(s: SetupUiState, local: HttpUrl?, public: HttpUrl?) {
        val request = LoginRequest(s.username.trim(), s.password)
        var failure = SetupError.UNREACHABLE
        for (url in listOfNotNull(local, public)) {
            try {
                val user = loginClient.login(url, request).user
                val access = user.accessToken
                val refresh = user.refreshToken
                if (access == null || refresh == null) return fail(SetupError.SERVER_TOO_OLD)
                val addresses = ServerAddresses(
                    local, public,
                    if (s.ssidBinding && local != null) s.keptSsids + setOfNotNull(s.currentSsid) else emptySet(),
                )
                val account = Account(
                    serverId = Account.computeServerId(addresses, user.id),
                    userId = user.id,
                    username = user.username,
                    isAdmin = user.type == "admin" || user.type == "root",
                    addresses = addresses,
                    allowCleartext = listOfNotNull(local, public).filter { !it.isHttps }.map { it.cleartextKey() }.toSet(),
                )
                try {
                    accounts.save(account, access, refresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return fail(SetupError.SAVE_FAILED)
                }
                sync.syncNow() // not awaited; runs in WorkManager
                _state.update { it.copy(loading = false, password = "", done = true) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // The server answered: wrong credentials are final, other errors try the next address.
                if (e.code() == 401 || e.code() == 403) return fail(SetupError.WRONG_LOGIN)
            } catch (_: Exception) {
                failure = SetupError.UNREACHABLE
            }
        }
        fail(failure)
    }

    private fun fail(error: SetupError) = _state.update { it.copy(loading = false, error = error) }

    /** Blank -> null. A missing scheme gets [defaultScheme] (http for the usually unencrypted local address). */
    private fun parse(raw: String, defaultScheme: String): HttpUrl? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val withScheme = if ("://" in t) t else "$defaultScheme://$t"
        return withScheme.toHttpUrlOrNull()
    }
}
