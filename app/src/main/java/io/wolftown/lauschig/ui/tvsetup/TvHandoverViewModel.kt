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
import io.wolftown.lauschig.serverqr.ServerQr
import io.wolftown.lauschig.serverqr.ServerQrResult
import io.wolftown.lauschig.tvsetup.HandoverSender
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

    data class Failed(val reason: TvHandoverError) : TvHandoverStep
}

enum class TvHandoverNote { CAMERA_REFUSED, NOT_A_TV_CODE, SERVER_CODE, OTHER_VERSION, NOT_HOME_NETWORK }

enum class TvHandoverError { WRONG_PASSWORD, SERVER_UNREACHABLE, NO_HOME_NETWORK, TV_UNREACHABLE, CODE_EXPIRED, TV_CANNOT_REACH_SERVER, TV_REFUSED, NO_REPLY, FAILED }

/**
 * The phone's side of setting up a TV. The password goes only to the active account's own server: the phone signs in
 * itself and hands the TV a new session's refresh token, never its own session and never the password (spec E13).
 */
@HiltViewModel
class TvHandoverViewModel(
    private val accounts: AccountStore,
    private val loginClient: LoginClient,
    private val send: suspend (TvSetupCode, PhoneHandover) -> SendResult,
    /** Ends the session made for the TV; never throws and is time-bounded ([ServerLogout.logoutProfile]). */
    private val endSession: suspend (Account, refreshToken: String) -> Unit,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, loginClient: LoginClient, sender: HandoverSender, logout: ServerLogout) :
        this(accounts, loginClient, sender::send, { account, refresh -> logout.logoutProfile(account, refresh) })

    private val _step = MutableStateFlow<TvHandoverStep>(TvHandoverStep.Intro())
    val step: StateFlow<TvHandoverStep> = _step.asStateFlow()

    /** The scanned code; only here, only until the handover ends or the page goes (the phone forgets the TV again). */
    private var code: TvSetupCode? = null

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
        val password = s.password
        // The password leaves the state as soon as it is used: whatever comes next, the field is empty.
        _step.value = s.copy(password = "", working = true, error = null)
        viewModelScope.launch {
            // Once confirmed it runs to its end, the page may go or not: a session made for the TV is either handed
            // over and taken, or ended again. Every step is time-bounded (login, connect, reply, logout).
            val next = withContext(NonCancellable) { handOver(code, password, s) }
            if (next !is TvHandoverStep.Confirm) this@TvHandoverViewModel.code = null
            _step.value = next
        }
    }

    private suspend fun handOver(code: TvSetupCode, password: String, s: TvHandoverStep.Confirm): TvHandoverStep {
        val account = accounts.account.first() ?: return TvHandoverStep.Failed(TvHandoverError.FAILED)
        val response = when (val login = signIn(account, password)) {
            is SignIn.Error -> return if (login.error == TvHandoverError.FAILED) TvHandoverStep.Failed(login.error)
            else s.copy(password = "", working = false, error = login.error)
            is SignIn.Ok -> login.response
        }
        val refresh = response.user.refreshToken ?: return TvHandoverStep.Failed(TvHandoverError.FAILED)
        // Another user than the account's: the password belongs to it, but the TV is not set up for someone else.
        if (response.user.id != account.userId) {
            endSession(account, refresh)
            return TvHandoverStep.Failed(TvHandoverError.FAILED)
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
        // The TV's answer is only a hint (spec E19): unless it says signed in, the session must not stay usable.
        endSession(account, refresh)
        return TvHandoverStep.Failed(
            when (result) {
                SendResult.NOT_ACCEPTED -> TvHandoverError.CODE_EXPIRED
                SendResult.SERVER_UNREACHABLE -> TvHandoverError.TV_CANNOT_REACH_SERVER
                SendResult.SIGN_IN_REFUSED -> TvHandoverError.TV_REFUSED
                SendResult.NO_HOME_NETWORK -> TvHandoverError.NO_HOME_NETWORK
                SendResult.NOT_SENT -> TvHandoverError.TV_UNREACHABLE
                SendResult.NO_REPLY -> TvHandoverError.NO_REPLY
                SendResult.FAILED, SendResult.SIGNED_IN -> TvHandoverError.FAILED
            },
        )
    }

    /** After a failure: a new scan, since the TV shows a new code (spec E16). */
    fun again() {
        if (_step.value !is TvHandoverStep.Failed) return
        code = null
        _step.value = TvHandoverStep.Intro()
    }

    private sealed interface SignIn {
        class Ok(val response: LoginResponse) : SignIn {
            override fun toString() = "Ok"
        }
        data class Error(val error: TvHandoverError) : SignIn
    }

    /** At the account's own addresses only, local first; plain http only where the account confirmed it. */
    private suspend fun signIn(account: Account, password: String): SignIn {
        val urls = listOfNotNull(account.addresses.local, account.addresses.public)
            .filter { it.isHttps || it.cleartextKey() in account.allowCleartext }
        if (urls.isEmpty()) return SignIn.Error(TvHandoverError.FAILED)
        val request = LoginRequest(account.username, password)
        for (url in urls) {
            try {
                return SignIn.Ok(loginClient.login(url, request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // The server answered: wrong credentials are final, other errors try the next address.
                if (e.code() == 401 || e.code() == 403) return SignIn.Error(TvHandoverError.WRONG_PASSWORD)
            } catch (_: Exception) {
                // Unreachable: the next address.
            }
        }
        return SignIn.Error(TvHandoverError.SERVER_UNREACHABLE)
    }

    /** The account's two addresses as the setup form writes them: the very text the TV will get. */
    private fun Account.servers(): Pair<String?, String?> =
        addresses.local?.let(ServerQr::plain) to addresses.public?.let(ServerQr::plain)
}
