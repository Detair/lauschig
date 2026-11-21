package io.wolftown.lauschig.ui.tvsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.absapi.auth.LoginRequest
import io.wolftown.lauschig.absapi.auth.LoginResponse
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.ServerLogout
import io.wolftown.lauschig.account.cleartextKey
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.belongsTo
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.serverqr.ServerQr
import io.wolftown.lauschig.serverqr.ServerQrResult
import io.wolftown.lauschig.tvsetup.HandoverSender
import io.wolftown.lauschig.tvsetup.HomeNetwork
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.tvsetup.SendResult
import io.wolftown.lauschig.tvsetup.TvSetupCode
import io.wolftown.lauschig.tvsetup.TvSetupCodeError
import io.wolftown.lauschig.tvsetup.TvSetupCodeResult
import io.wolftown.lauschig.tvsetup.TvSetupCodes
import io.wolftown.lauschig.ui.setup.LoginClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import retrofit2.HttpException
import javax.inject.Inject

/** Where „Fernseher einrichten“ stands (docs/tv-einrichtung-mit-dem-handy.md). */
sealed interface TvHandoverStep {
    /** What the page explains before the camera opens; [note] says why the last scan was not taken. */
    data class Intro(val note: TvHandoverNote? = null) : TvHandoverStep

    data object Scanning : TvHandoverStep

    /**
     * A TV's code was scanned: [number] (four digits, as the TV shows them), the account's [servers] (local first) and
     * [username]; [password] while it is typed.
     */
    data class Confirm(
        val number: String,
        val servers: List<String>,
        val username: String,
        val password: String = "",
        val working: Boolean = false,
        val error: TvHandoverError? = null,
    ) : TvHandoverStep {
        // A data class would print the password, and the addresses stay out of logs as everywhere else.
        override fun toString(): String = "Confirm(working=$working, error=$error)"
    }

    data object Done : TvHandoverStep

    /** [sessionMayBeOpen]: a session was made for the TV, and that it has ended could not be confirmed (spec E19). */
    data class Failed(val reason: TvHandoverError, val sessionMayBeOpen: Boolean = false) : TvHandoverStep
}

enum class TvHandoverNote { CAMERA_REFUSED, NOT_A_TV_CODE, SERVER_CODE, OTHER_VERSION, NOT_HOME_NETWORK }

enum class TvHandoverError {
    WRONG_PASSWORD, SERVER_UNREACHABLE, NO_HOME_NETWORK, TV_UNREACHABLE, CODE_EXPIRED, TV_CANNOT_REACH_SERVER, TV_REFUSED,
    DECLINED_ON_TV, NO_REPLY, FAILED,
}

/**
 * The phone's side of setting up a TV. The password goes only to the active account's own server, at the address the app
 * is connected through (spec E20): the phone signs in itself and hands the TV a new session's refresh token, never its own
 * session and never the password (E13). Unless the TV says it signed in, the phone tries to end that session again (E19).
 */
@HiltViewModel
class TvHandoverViewModel(
    private val accounts: AccountStore,
    private val loginClient: LoginClient,
    private val send: suspend (TvSetupCode, PhoneHandover) -> SendResult,
    /** Logs a session out at one address of the account; never throws, time-bounded ([ServerLogout.logoutAt]). True: confirmed. */
    private val logoutAt: suspend (Account, HttpUrl, refreshToken: String) -> Boolean,
    /** Where the app is connected now; the password goes nowhere else. */
    private val connection: () -> ConnectionState,
    /** The phone is in a Wi-Fi or cable network that is no VPN: without one no packet can reach a TV. */
    private val hasHomeNetwork: () -> Boolean,
) : ViewModel() {
    @Inject constructor(
        accounts: AccountStore, loginClient: LoginClient, sender: HandoverSender, logout: ServerLogout, api: ApiProvider, network: HomeNetwork,
    ) : this(
        accounts, loginClient, sender::send, logout::logoutAt, { api.connectionState.value }, { network.ownAddress() != null },
    )

    private val _step = MutableStateFlow<TvHandoverStep>(TvHandoverStep.Intro())
    val step: StateFlow<TvHandoverStep> = _step.asStateFlow()

    /** The scanned code; only here, only until the handover ends or the page goes (the phone forgets the TV again). */
    private var code: TvSetupCode? = null

    /** The account the question named: only that one is used (another may have become active meanwhile). */
    private var confirmedServerId: String? = null

    fun startScan() {
        if (_step.value is TvHandoverStep.Intro) _step.value = TvHandoverStep.Scanning
    }

    fun cameraRefused() {
        if (_step.value is TvHandoverStep.Intro) _step.value = TvHandoverStep.Intro(TvHandoverNote.CAMERA_REFUSED)
    }

    fun scanClosed() {
        if (_step.value == TvHandoverStep.Scanning) _step.value = TvHandoverStep.Intro()
    }

    /** The text the camera found; read once and kept nowhere. */
    fun onScanned(text: String) {
        if (_step.value != TvHandoverStep.Scanning) return
        when (val result = TvSetupCodes.read(text)) {
            is TvSetupCodeResult.Rejected -> _step.value = TvHandoverStep.Intro(note(result.reason, text))
            is TvSetupCodeResult.Code -> viewModelScope.launch {
                val account = accounts.account.first()
                if (account == null) {
                    _step.value = TvHandoverStep.Failed(TvHandoverError.FAILED)
                    return@launch
                }
                code = result.code
                confirmedServerId = account.serverId
                _step.value = TvHandoverStep.Confirm(result.code.number, account.servers().toList().filterNotNull(), account.username)
            }
        }
    }

    private fun note(reason: TvSetupCodeError, text: String): TvHandoverNote = when (reason) {
        TvSetupCodeError.UNKNOWN_VERSION -> TvHandoverNote.OTHER_VERSION
        TvSetupCodeError.NOT_HOME_NETWORK -> TvHandoverNote.NOT_HOME_NETWORK
        // The other code the app shows: worth saying it is the wrong one, not just no code.
        else -> if (ServerQr.read(text) is ServerQrResult.Addresses) TvHandoverNote.SERVER_CODE else TvHandoverNote.NOT_A_TV_CODE
    }

    fun onPassword(value: String) {
        val s = _step.value as? TvHandoverStep.Confirm ?: return
        if (s.working) return
        _step.value = s.copy(password = value, error = null)
    }

    fun send() {
        val s = _step.value as? TvHandoverStep.Confirm ?: return
        if (s.working || s.password.isEmpty()) return
        val code = code ?: return
        val confirmed = confirmedServerId ?: return
        // The password leaves the state now, and the coroutine holds it only until the login has used it.
        val password = OneShot(s.password)
        val shown = s.copy(password = "", working = true, error = null)
        _step.value = shown
        viewModelScope.launch {
            // Once confirmed it runs to its end, the page may go or not: a session made for the TV is either handed
            // over and taken, or ended again. Every step is time-bounded (login, connect, reply, ending).
            val next = withContext(NonCancellable) { handOver(code, confirmed, password, shown) }
            if (next !is TvHandoverStep.Confirm) this@TvHandoverViewModel.code = null
            _step.value = next
        }
    }

    private suspend fun handOver(code: TvSetupCode, confirmed: String, password: OneShot, shown: TvHandoverStep.Confirm): TvHandoverStep {
        fun stay(error: TvHandoverError) = shown.copy(working = false, error = error)
        val account = accounts.account.first()
        if (account == null || account.serverId != confirmed) {
            password.take()
            return TvHandoverStep.Failed(TvHandoverError.FAILED)
        }
        // Checked before the password goes anywhere: without a home network nothing could reach the TV (E20).
        if (!hasHomeNetwork()) {
            password.take()
            return stay(TvHandoverError.NO_HOME_NETWORK)
        }
        // Only the address the app is connected through: it honours the account's Wi-Fi binding and its confirmed http
        // addresses. A local address on someone else's network could be answered by any device there (E20).
        val online = (connection() as? ConnectionState.Online)?.takeIf { it.belongsTo(account.addresses) }
        val baseUrl = online?.baseUrl?.takeIf { it.isHttps || it.cleartextKey() in account.allowCleartext }
        if (baseUrl == null) {
            password.take()
            return stay(TvHandoverError.SERVER_UNREACHABLE)
        }
        val response = when (val login = signIn(account, baseUrl, password)) {
            is SignIn.Error -> return stay(login.error)
            is SignIn.Ok -> login.response
        }
        val refresh = response.user.refreshToken ?: return TvHandoverStep.Failed(TvHandoverError.FAILED)
        // Another user than the account's: the password belongs to it, but the TV is not set up for someone else.
        if (response.user.id != account.userId) {
            return TvHandoverStep.Failed(TvHandoverError.FAILED, sessionMayBeOpen = !endSession(account, baseUrl, refresh))
        }
        val (local, public) = account.servers()
        val result = try {
            send(code, PhoneHandover(local = local, public = public, refresh = refresh))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SendResult.FAILED
        }
        if (result == SendResult.SIGNED_IN) return TvHandoverStep.Done
        // The TV's answer is only a hint (E19): unless it says signed in, the session must not stay usable.
        val ended = endSession(account, baseUrl, refresh)
        val reason = when (result) {
            SendResult.NOT_ACCEPTED -> TvHandoverError.CODE_EXPIRED
            SendResult.SERVER_UNREACHABLE -> TvHandoverError.TV_CANNOT_REACH_SERVER
            SendResult.SIGN_IN_REFUSED -> TvHandoverError.TV_REFUSED
            SendResult.DECLINED -> TvHandoverError.DECLINED_ON_TV
            SendResult.NO_HOME_NETWORK -> TvHandoverError.NO_HOME_NETWORK
            SendResult.NOT_SENT -> TvHandoverError.TV_UNREACHABLE
            SendResult.NO_REPLY -> TvHandoverError.NO_REPLY
            SendResult.FAILED, SendResult.SIGNED_IN -> TvHandoverError.FAILED
        }
        return TvHandoverStep.Failed(reason, sessionMayBeOpen = !ended)
    }

    /**
     * Audiobookshelf logs out only the session whose current refresh token is sent, and whoever exchanged the handed-over
     * token [handed] has rotated it. For 10 minutes after a rotation the old token still yields the current one, without
     * rotating again: so exchange it once more, then log out with what comes back. True only when both worked. If the
     * exchange fails, a logout with [handed] still covers a token nobody exchanged, but nothing confirms it.
     */
    private suspend fun endSession(account: Account, baseUrl: HttpUrl, handed: String): Boolean {
        val current = try {
            loginClient.refresh(baseUrl, handed).user.refreshToken
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (current == null) {
            logoutAt(account, baseUrl, handed)
            return false
        }
        return logoutAt(account, baseUrl, current)
    }

    /** After a failure: a new scan, since the TV shows a new code (spec E16). */
    fun again() {
        if (_step.value !is TvHandoverStep.Failed) return
        code = null
        confirmedServerId = null
        _step.value = TvHandoverStep.Intro()
    }

    private sealed interface SignIn {
        class Ok(val response: LoginResponse) : SignIn {
            override fun toString() = "Ok"
        }
        data class Error(val error: TvHandoverError) : SignIn
    }

    /** One login at [baseUrl]. The password is taken out of [password] here, so nothing holds it after this returns. */
    private suspend fun signIn(account: Account, baseUrl: HttpUrl, password: OneShot): SignIn {
        val request = LoginRequest(account.username, password.take() ?: return SignIn.Error(TvHandoverError.FAILED))
        return try {
            SignIn.Ok(loginClient.login(baseUrl, request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // The server answered: a wrong password, or another error at the one address there is.
            SignIn.Error(if (e.code() == 401 || e.code() == 403) TvHandoverError.WRONG_PASSWORD else TvHandoverError.SERVER_UNREACHABLE)
        } catch (_: Exception) {
            SignIn.Error(TvHandoverError.SERVER_UNREACHABLE)
        }
    }

    /** The account's two addresses as the setup form writes them: the very text the TV will get. */
    private fun Account.servers(): Pair<String?, String?> =
        addresses.local?.let(ServerQr::plain) to addresses.public?.let(ServerQr::plain)

    /** Holds the typed password until the one login that uses it; a second [take] gets nothing. */
    private class OneShot(private var value: String?) {
        fun take(): String? = value.also { value = null }
        override fun toString() = "OneShot"
    }
}
