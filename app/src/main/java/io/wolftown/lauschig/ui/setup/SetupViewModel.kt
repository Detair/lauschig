package io.wolftown.lauschig.ui.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.absapi.auth.StatusResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountSignOut
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.NetworkInfo
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.ui.nav.Setup
import io.wolftown.lauschig.ui.nav.SetupMode
import io.wolftown.lauschig.ui.profiles.ProfileOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import javax.inject.Inject

enum class SetupError { INVALID_ADDRESS, UNREACHABLE, WRONG_LOGIN, SERVER_TOO_OLD, SAVE_FAILED, SSO_REDIRECT_NOT_ALLOWED, SSO_FAILED }

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
    /** The server offers OpenID sign-in (from `GET /status`). */
    val ssoAvailable: Boolean = false,
    /** The server's own button label, or null for the default. */
    val ssoButtonText: String? = null,
    /** One-shot: the screen opens this in a Custom Tab and reports back with [SetupViewModel.onSsoLaunched]. */
    val ssoLaunchUrl: String? = null,
    /** One more profile ([SetupMode.ADD_PROFILE]): the active account is set aside instead of replaced. */
    val addProfile: Boolean = false,
) {
    val canLogin: Boolean
        get() = !loading && (local.isNotBlank() || public.isNotBlank()) && username.isNotBlank() && password.isNotEmpty()
}

/** The form's mode, from its route: it survives a rotation, a process death and the browser of an SSO sign-in. */
fun setupMode(savedState: SavedStateHandle): SetupMode = savedState.toRoute<Setup>().mode

@HiltViewModel
class SetupViewModel(
    private val accounts: AccountStore,
    private val loginClient: LoginClient,
    private val network: NetworkInfo,
    private val sync: SyncTrigger,
    private val oidc: OidcSignIn,
    private val callbacks: OidcCallbackBus,
    private val signOut: AccountSignOut,
    private val addProfile: suspend (Account, accessToken: String, refreshToken: String) -> Unit = { _, _, _ -> },
    mode: SetupMode = SetupMode.SIGN_IN,
) : ViewModel() {
    @Inject constructor(
        savedState: SavedStateHandle, accounts: AccountStore, loginClient: LoginClient, network: NetworkInfo, sync: SyncTrigger,
        oidc: OidcSignIn, callbacks: OidcCallbackBus, signOut: AccountSignOut, operations: ProfileOperations,
    ) : this(accounts, loginClient, network, sync, oidc, callbacks, signOut, operations::add, setupMode(savedState))

    private val _state = MutableStateFlow(SetupUiState(addProfile = mode == SetupMode.ADD_PROFILE))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private val confirmed = mutableSetOf<String>()
    private var afterCleartext: (() -> Unit)? = null
    private var statusJob: Job? = null
    private var resumeJob: Job? = null
    private var ssoFinishing = false

    /** The Custom Tab is open and no callback has arrived yet. */
    private var awaitingCallback = false

    init {
        // Re-auth: pre-fill everything but the password from the current account (read locally, no network). Read once: the
        // form belongs to that account's pages and goes with them. Another profile: only where the server is (the family's
        // server, usually); without an active account, the addresses of a saved one.
        viewModelScope.launch {
            val adding = mode == SetupMode.ADD_PROFILE
            val a = accounts.account.first() ?: (if (adding) accounts.saved.first().firstOrNull() else null) ?: return@launch
            confirmed += a.allowCleartext
            val ssids = a.addresses.homeSsids
            _state.update {
                if (it.local.isNotEmpty() || it.public.isNotEmpty() || it.username.isNotEmpty()) it
                else it.copy(
                    local = a.addresses.local?.toString()?.trimEnd('/').orEmpty(),
                    public = a.addresses.public?.toString()?.trimEnd('/').orEmpty(),
                    username = if (adding) "" else a.username,
                    ssidBinding = ssids.isNotEmpty(),
                    currentSsid = ssids.firstOrNull(),
                    keptSsids = ssids,
                )
            }
            checkStatus()
        }
        // The deep link lauschig://oauth (also one that restarted the process while the tab was open).
        viewModelScope.launch {
            callbacks.pending.filterNotNull().collect {
                callbacks.take()?.let(::onSsoCallback)
            }
        }
    }

    fun onLocalChanged(v: String) { _state.update { it.copy(local = v, error = null) }; checkStatus() }
    fun onPublicChanged(v: String) { _state.update { it.copy(public = v, error = null) }; checkStatus() }
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
        if (needsCleartextConfirmation(listOfNotNull(local, public), ::login)) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch { attempt(s, local, public) }
    }

    /** Asks the user first if a plain-HTTP address is not confirmed yet; [retry] runs once they agree. */
    private fun needsCleartextConfirmation(urls: List<HttpUrl>, retry: () -> Unit): Boolean {
        val unconfirmed = urls.filter { !it.isHttps && it.cleartextKey() !in confirmed }.map { it.cleartextKey() }.distinct()
        if (unconfirmed.isEmpty()) return false
        afterCleartext = retry
        _state.update { it.copy(cleartextPrompt = unconfirmed) }
        return true
    }

    fun confirmCleartext() {
        val prompt = _state.value.cleartextPrompt ?: return
        confirmed += prompt
        _state.update { it.copy(cleartextPrompt = null) }
        val next = afterCleartext ?: ::login
        afterCleartext = null
        next()
        checkStatus()
    }

    fun dismissCleartext() {
        afterCleartext = null
        _state.update { it.copy(cleartextPrompt = null) }
    }

    /**
     * Asks `GET /status` which sign-in methods the server offers (debounced; errors are ignored). Plain-HTTP addresses
     * are only asked once the user has confirmed them.
     */
    private fun checkStatus() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            delay(STATUS_DEBOUNCE_MS)
            val s = _state.value
            val urls = listOfNotNull(parse(s.public, "https"), parse(s.local, "http"))
                .filter { it.isHttps || it.cleartextKey() in confirmed }
            var status: StatusResponse? = null
            for (url in urls) {
                status = try {
                    loginClient.status(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (status != null) break
            }
            val sso = status != null && "openid" in status.authMethods
            _state.update {
                it.copy(
                    ssoAvailable = sso,
                    ssoButtonText = if (sso) status?.authFormData?.authOpenIDButtonText?.takeIf(String::isNotBlank) else null,
                )
            }
        }
    }

    /** SSO button: asks the server for the identity provider's page (public address first), the screen then opens it. */
    fun loginWithSso() {
        val s = _state.value
        if (s.loading) return
        val local = parse(s.local, "http")
        val public = parse(s.public, "https")
        if ((s.local.isNotBlank() && local == null) || (s.public.isNotBlank() && public == null) || (local == null && public == null)) {
            _state.update { it.copy(error = SetupError.INVALID_ADDRESS) }
            return
        }
        if (needsCleartextConfirmation(listOfNotNull(local, public), ::loginWithSso)) return
        _state.update { it.copy(loading = true, error = null) }
        val flow = OidcFlowContext(
            local = local?.toString(), public = public?.toString(),
            ssidBinding = s.ssidBinding, currentSsid = s.currentSsid, keptSsids = s.keptSsids,
            addProfile = s.addProfile,
        )
        viewModelScope.launch {
            var failure = SetupError.UNREACHABLE
            for (url in listOfNotNull(public, local)) {
                when (val r = oidc.start(url, flow)) {
                    is OidcStart.Launch -> {
                        _state.update { it.copy(ssoLaunchUrl = r.url) }
                        return@launch
                    }
                    OidcStart.RedirectNotAllowed -> { failure = SetupError.SSO_REDIRECT_NOT_ALLOWED; break }
                    OidcStart.Unreachable -> Unit
                }
            }
            fail(failure)
        }
    }

    /** The screen has handed the URL to the Custom Tab. */
    fun onSsoLaunched() {
        awaitingCallback = true
        _state.update { it.copy(ssoLaunchUrl = null) }
    }

    /** The Custom Tab could not be opened (no browser). */
    fun onSsoLaunchFailed() {
        awaitingCallback = false
        _state.update { it.copy(ssoLaunchUrl = null, loading = false, error = SetupError.SSO_FAILED) }
    }

    /**
     * Back in the foreground. A callback arrives (onNewIntent) before this; if none did within a second the user closed
     * the tab, so the spinner stops without an error.
     */
    fun onResumed() {
        if (!awaitingCallback) return
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            delay(RESUME_GRACE_MS)
            if (awaitingCallback) {
                awaitingCallback = false
                _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun onSsoCallback(cb: OidcCallback) {
        if (_state.value.done || ssoFinishing) return // replayed after a recreation, or a second link while one is running
        ssoFinishing = true
        viewModelScope.launch {
            try {
                // Foreign or stale links change nothing, not even the spinner of a running password login.
                if (!oidc.matchesPending(cb)) return@launch
                awaitingCallback = false
                resumeJob?.cancel()
                _state.update { it.copy(loading = true, error = null) }
                val result = try {
                    oidc.finish(cb)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OidcException) {
                    return@launch fail(if (e.reason == OidcException.Reason.UNREACHABLE) SetupError.UNREACHABLE else SetupError.SSO_FAILED)
                } catch (_: Exception) {
                    return@launch fail(SetupError.SSO_FAILED)
                }
                // Addresses and SSID binding are the ones the flow started with (survives a process restart).
                val f = result.flow
                val local = f.local?.toHttpUrlOrNull()
                val public = f.public?.toHttpUrlOrNull()
                val (l, p) =
                    if (local == null && public == null) (if (result.baseUrl.isHttps) null to result.baseUrl else result.baseUrl to null)
                    else local to public
                // So is what it started as: the form may have come back another way, the old account must not be signed out.
                val s = _state.value.copy(ssidBinding = f.ssidBinding, currentSsid = f.currentSsid, keptSsids = f.keptSsids, addProfile = f.addProfile)
                complete(s, l, p, result.response)
            } finally {
                ssoFinishing = false
            }
        }
    }

    private suspend fun attempt(s: SetupUiState, local: HttpUrl?, public: HttpUrl?) {
        val request = LoginRequest(s.username.trim(), s.password)
        var failure = SetupError.UNREACHABLE
        for (url in listOfNotNull(local, public)) {
            try {
                complete(s, local, public, loginClient.login(url, request))
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

    /** Shared by password and SSO login: validates the tokens, saves the account and finishes setup. */
    private suspend fun complete(s: SetupUiState, local: HttpUrl?, public: HttpUrl?, response: LoginResponse) {
        val user = response.user
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
        // The form is one of the pages of the account before: when the account changes it goes, this view model with it. The
        // sign-in has happened by then and goes through to the end.
        val saved = withContext(NonCancellable) {
            val stored = try {
                // One more profile: the active account is set aside and stays signed in. A re-auth with another account or
                // another public address is another server id: the old one is signed out keeping its data (it then shows
                // under „Downloads früherer Konten“), so none of its work keeps running and nothing of it is left
                // unreachable – and replaced in the same step, so the app never shows "no account".
                // One account operation, after any other one still running (a sign-out, a profile switch).
                if (s.addProfile) addProfile(account, access, refresh) else signOut.signIn(account, access, refresh)
                true
            } catch (e: Exception) {
                false
            }
            if (stored) sync.syncNow() // not awaited; runs in WorkManager
            stored
        }
        if (!saved) return fail(SetupError.SAVE_FAILED)
        _state.update { it.copy(loading = false, password = "", done = true) }
    }

    private fun fail(error: SetupError) = _state.update { it.copy(loading = false, error = error) }

    /** Blank -> null. A missing scheme gets [defaultScheme] (http for the usually unencrypted local address). */
    private fun parse(raw: String, defaultScheme: String): HttpUrl? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val withScheme = if ("://" in t) t else "$defaultScheme://$t"
        return withScheme.toHttpUrlOrNull()
    }

    private companion object {
        const val STATUS_DEBOUNCE_MS = 500L
        const val RESUME_GRACE_MS = 1_000L
    }
}

/**
 * An SSO sign-in came back from the browser: its answer waits ([OidcCallbackBus]) and no form is there to take it, when the
 * process died and no page was saved. Only the answer to the attempt we started counts; a foreign link opens nothing.
 */
@HiltViewModel
class SsoReturnViewModel @Inject constructor(callbacks: OidcCallbackBus, oidc: OidcSignIn) : ViewModel() {
    val waiting: StateFlow<Boolean> = callbacks.pending
        .map { entry -> entry != null && oidc.matchesPending(entry.callback) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
