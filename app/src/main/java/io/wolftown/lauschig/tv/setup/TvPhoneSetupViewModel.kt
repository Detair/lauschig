package io.wolftown.lauschig.tv.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.tvsetup.HandoverListener
import io.wolftown.lauschig.tvsetup.HandoverReply
import io.wolftown.lauschig.tvsetup.HomeNetwork
import io.wolftown.lauschig.tvsetup.ListenResult
import io.wolftown.lauschig.tvsetup.PhoneHandover
import io.wolftown.lauschig.tvsetup.TvSetupCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import javax.inject.Inject

sealed interface TvPhoneSetupState {
    /** The port is being opened; no code yet. */
    data object Starting : TvPhoneSetupState

    /** Neither Wi-Fi nor cable into a home network: there is no address a phone could reach. */
    data object NoHomeNetwork : TvPhoneSetupState

    /** [code] is the QR code's text, [number] the four digits both screens show. */
    data class Waiting(val code: String, val number: String) : TvPhoneSetupState {
        // The code holds the TV's address and key: out of logs and crash reports.
        override fun toString(): String = "Waiting(…)"
    }

    /**
     * A packet came in, and the TV asks before it does anything with it (spec E17): nothing goes to a server until
     * [TvPhoneSetupViewModel.confirm]. [servers] are the handover's addresses, local first.
     */
    data class Confirming(val servers: List<TvPhoneServer>) : TvPhoneSetupState {
        // The addresses stay out of logs and crash reports.
        override fun toString(): String = "Confirming(${servers.size})"
    }

    /** Confirmed; the TV is exchanging the session at the server. */
    data object SigningIn : TvPhoneSetupState

    data class Stopped(val reason: TvPhoneSetupStop) : TvPhoneSetupState
}

/** One address of a handover as the question shows it; [encrypted]: https. */
data class TvPhoneServer(val address: String, val encrypted: Boolean) {
    override fun toString(): String = "TvPhoneServer(encrypted=$encrypted)"
}

enum class TvPhoneSetupStop { EXPIRED, TOO_MANY_ATTEMPTS, SERVER_UNREACHABLE, SIGN_IN_REFUSED, FAILED, DECLINED }

/** [HandoverListener.listen]'s shape, so that tests can stand in for the network. */
typealias HandoverListen = suspend (bind: InetAddress, onReady: (TvSetupCode) -> Unit, signIn: suspend (PhoneHandover) -> HandoverReply) -> ListenResult

/**
 * The code page's listener. A code lives only while the page is on screen ([show] to [hide]): its key is in memory only then,
 * and coming back gives a new one.
 */
@HiltViewModel
class TvPhoneSetupViewModel internal constructor(
    private val network: HomeNetwork,
    private val listen: HandoverListen,
    /** How long the question waits for the remote; then it counts as a no. */
    private val confirmMs: Long = CONFIRM_MS,
) : ViewModel() {
    @Inject constructor(network: HomeNetwork, listener: HandoverListener) : this(network, listener::listen)

    private val _state = MutableStateFlow<TvPhoneSetupState>(TvPhoneSetupState.Starting)
    val state: StateFlow<TvPhoneSetupState> = _state.asStateFlow()

    // The listener reports from an IO thread. A report of a run that was ended already must not bring back its code, so each
    // run has a number, and a report counts only while its number is the current one; the lock makes check and write one step.
    private val lock = Any()
    private var run = 0
    private var job: Job? = null
    private var signIn: (suspend (PhoneHandover) -> HandoverReply)? = null

    /** The open question's answer, while [TvPhoneSetupState.Confirming] shows; under [lock]. */
    private var answer: CompletableDeferred<Boolean>? = null

    /** The page is on screen: a new code. A second call without [hide] keeps the code that is there. */
    fun show(signIn: suspend (PhoneHandover) -> HandoverReply) {
        this.signIn = signIn
        if (job == null) start()
    }

    /** The page is gone: the port closes, the key is forgotten, and the code disappears with it. */
    fun hide() {
        signIn = null
        stop()
    }

    /** Ends the current code, if any, and shows a new one. */
    fun newCode() {
        if (signIn == null) return
        stop()
        start()
    }

    /** "Set up" on the question: only now does the TV talk to the server. */
    fun confirm() = answer(true)

    /** "Cancel" or Back on the question: the phone hears so, and no server is asked. */
    fun decline() = answer(false)

    private fun answer(yes: Boolean) {
        synchronized(lock) { answer }?.complete(yes)
    }

    override fun onCleared() = hide()

    private fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) {
            run++
            // A question left open counts as a no; the cancelled listener tells the phone.
            answer?.complete(false)
            answer = null
            _state.value = TvPhoneSetupState.Starting
        }
    }

    private fun start() {
        val signIn = signIn ?: return
        val mine = synchronized(lock) {
            _state.value = TvPhoneSetupState.Starting
            ++run
        }
        fun report(state: TvPhoneSetupState) = synchronized(lock) { if (run == mine) _state.value = state }
        job = viewModelScope.launch {
            val address = network.ownAddress()
            if (address == null) return@launch report(TvPhoneSetupState.NoHomeNetwork)
            val result = listen(
                address,
                { code -> report(TvPhoneSetupState.Waiting(code.text, code.number)) },
                { handover ->
                    if (ask(mine, handover)) {
                        report(TvPhoneSetupState.SigningIn)
                        signIn(handover)
                    } else {
                        HandoverReply.DECLINED
                    }
                },
            )
            report(
                when (result) {
                    // The page leaves for the account's pages in a moment; until then it says it is signing in.
                    is ListenResult.Finished -> when (result.reply) {
                        HandoverReply.SIGNED_IN -> TvPhoneSetupState.SigningIn
                        HandoverReply.SERVER_UNREACHABLE -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.SERVER_UNREACHABLE)
                        HandoverReply.SIGN_IN_REFUSED -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.SIGN_IN_REFUSED)
                        HandoverReply.DECLINED -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.DECLINED)
                        HandoverReply.NOT_ACCEPTED, HandoverReply.FAILED -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.FAILED)
                    }
                    ListenResult.Expired -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.EXPIRED)
                    ListenResult.TooManyAttempts -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.TOO_MANY_ATTEMPTS)
                    ListenResult.Failed -> TvPhoneSetupState.Stopped(TvPhoneSetupStop.FAILED)
                },
            )
        }
    }

    /**
     * The sender of a packet is not checked, so the person at the TV says yes before anything happens (spec E17): with a
     * no, Back or no answer in [confirmMs], no server is asked. True: confirmed, for the run [mine].
     */
    private suspend fun ask(mine: Int, handover: PhoneHandover): Boolean {
        val asked = CompletableDeferred<Boolean>()
        val servers = listOfNotNull(handover.local, handover.public).map {
            TvPhoneServer(it.removeSuffix("/"), encrypted = it.startsWith("https://", ignoreCase = true))
        }
        val open = synchronized(lock) {
            (run == mine).also { current ->
                if (current) {
                    answer = asked
                    _state.value = TvPhoneSetupState.Confirming(servers)
                }
            }
        }
        if (!open) return false
        val yes = withTimeoutOrNull(confirmMs) { asked.await() } == true
        synchronized(lock) { if (answer === asked) answer = null }
        return yes
    }

    private companion object {
        const val CONFIRM_MS = 60_000L
    }
}
