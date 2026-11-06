package io.wolftown.lauschig.ui.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountData
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.PinCheck
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.account.displayHost
import io.wolftown.lauschig.account.isValidPin
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One profile as the sheet and the picker show it. [host]: only when the profiles are on different servers. */
@Immutable
data class ProfileRow(
    val serverId: String,
    val name: String,
    val host: String?,
    val active: Boolean,
    val needsReauth: Boolean,
    val protection: ProfileLockKind = ProfileLockKind.NONE,
)

/** What a removal takes along. [unsynced] null: could not be checked. */
data class RemovalFacts(val downloadBytes: Long, val unsynced: Int?, val onMissingCard: Boolean)

/** The question before a removal; [facts] null while they are read. */
data class RemovalQuestion(val row: ProfileRow, val facts: RemovalFacts?)

/** What the PIN prompt says below the field. */
sealed interface PinNotice {
    data class Wrong(val triesBeforeWait: Int) : PinNotice
    data class Wait(val ms: Long) : PinNotice
    data object Ready : PinNotice
    data object CheckFailed : PinNotice
}

/** What is being asked before a profile is entered, removed or its protection changed. Never holds an entered PIN. */
sealed interface GatePrompt {
    val row: ProfileRow

    /** [waitMs] > 0: the wait is on, nothing can be entered. */
    data class Pin(override val row: ProfileRow, val checking: Boolean = false, val notice: PinNotice? = null, val waitMs: Long = 0) : GatePrompt

    /** The system's prompt is due. [asked]: an answer from before this does not count. */
    data class Device(override val row: ProfileRow, val asked: Long = nextMoment(), val confirmingNew: Boolean = false) : GatePrompt

    /** No other way in than the sign-in of this account. */
    data class SignIn(override val row: ProfileRow, val reason: SignInReason) : GatePrompt
}

/** Changing the protection of the active profile, after the gate. */
sealed interface ProtectionStep {
    val row: ProfileRow

    data class Choose(override val row: ProfileRow, val current: ProfileLockKind, val deviceLockAvailable: Boolean, val saveFailed: Boolean = false) : ProtectionStep

    data class NewPin(override val row: ProfileRow, val saving: Boolean = false) : ProtectionStep
}

/** One profile needs no protection: nothing to switch from. The row stays while there is one, so it can be turned off. */
fun protectionRow(rows: List<ProfileRow>?): ProfileRow? {
    val active = rows?.firstOrNull { it.active } ?: return null
    return active.takeIf { rows.size >= 2 || it.protection != ProfileLockKind.NONE }
}

/** The rows in an order that does not change with the active profile. */
internal fun profileRows(
    active: Account?,
    saved: List<Account>,
    activeNeedsReauth: Boolean,
    savedNeedingReauth: Set<String>,
    protections: Map<String, ProfileLockKind> = emptyMap(),
): List<ProfileRow> {
    val all = listOfNotNull(active) + saved
    val severalServers = all.distinctBy { it.displayHost }.size > 1
    return all.map {
        val isActive = it.serverId == active?.serverId
        ProfileRow(
            it.serverId, it.username, it.displayHost.takeIf { severalServers }, isActive,
            if (isActive) activeNeedsReauth else it.serverId in savedNeedingReauth,
            protections[it.serverId] ?: ProfileLockKind.NONE,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, ProfileRow::name).thenBy(ProfileRow::serverId))
}

/** The profile the start page shows beside the gear: only from two profiles on, with one nothing changes there. */
fun headerProfile(rows: List<ProfileRow>?): ProfileRow? = rows?.takeIf { it.size >= 2 }?.firstOrNull { it.active }

/**
 * The profiles for the sheet and the picker, and what to say about a running operation. These live in [ProfileOperations]:
 * a screen is recreated, an operation goes on.
 */
@HiltViewModel
class ProfilesViewModel(
    accounts: AccountStore,
    private val operations: ProfileOperations,
    private val gate: ProfileGate,
    private val removalFacts: suspend (serverId: String) -> RemovalFacts,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, operations: ProfileOperations, gate: ProfileGate, data: AccountData) : this(
        accounts, operations, gate,
        { RemovalFacts(data.downloadBytes(it), data.unsynced(it), data.downloadsOnMissingVolume(it)) },
    )

    /** Null until the first local read. */
    val profiles: StateFlow<List<ProfileRow>?> =
        combine(
            combine(accounts.account, accounts.saved, accounts.needsReauth, accounts.savedNeedingReauth, ::Accounts),
            gate.kinds,
        ) { a, protections -> profileRows(a.active, a.saved, a.needsReauth, a.savedNeedingReauth, protections) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private class Accounts(val active: Account?, val saved: List<Account>, val needsReauth: Boolean, val savedNeedingReauth: Set<String>)

    val busy: StateFlow<ProfileWork?> = operations.running
    val outcome: StateFlow<ProfileOutcome?> = operations.outcome

    private val _removal = MutableStateFlow<RemovalQuestion?>(null)
    val removal: StateFlow<RemovalQuestion?> = _removal.asStateFlow()
    private var factsJob: Job? = null

    private val _gate = MutableStateFlow<GatePrompt?>(null)

    /** What is being asked right now. Only in memory: a process death leaves the account that was active, and no question. */
    val gatePrompt: StateFlow<GatePrompt?> = _gate.asStateFlow()

    private val _protection = MutableStateFlow<ProtectionStep?>(null)

    /** The protection of the active profile is being changed; the gate has been passed. */
    val protection: StateFlow<ProtectionStep?> = _protection.asStateFlow()

    /** What waits behind the prompt. */
    private var passed: (() -> Unit)? = null

    /** From the tap until the store's answer. */
    private var asking = false
    private var gateJob: Job? = null
    private var waitJob: Job? = null

    init {
        // It lives as long as the activity, and was asked of the account before. The running operations never let this
        // happen under the open question, a late SSO answer can. So can a prompt, or the choice of a protection: both
        // belong to the account before.
        viewModelScope.launch {
            accounts.account.map { it?.serverId }.distinctUntilChanged().drop(1).collect {
                cancelRemove()
                cancelGate()
                cancelProtection()
            }
        }
    }

    /**
     * Everything that goes into or removes another profile, or changes a protection, passes here (Phase 7, Task 3): [ProfileGate]
     * says what stands before it, read at this moment, and [action] runs only behind that. Nothing has happened until then; a
     * process death on the way leaves everything as it was.
     */
    private fun throughGate(row: ProfileRow, action: () -> Unit) {
        if (asking || _gate.value != null) return
        asking = true
        gateJob = viewModelScope.launch {
            try {
                when (val step = gate.ask(row.serverId)) {
                    GateStep.Open -> action()
                    is GateStep.Pin -> {
                        passed = action
                        // During a wait its words come one step later: only a line that was there before is read out.
                        _gate.value = GatePrompt.Pin(row, waitMs = step.waitMs)
                        if (step.waitMs > 0) followWait(row)
                    }
                    GateStep.DeviceLock -> {
                        passed = action
                        _gate.value = GatePrompt.Device(row)
                    }
                    is GateStep.SignIn -> _gate.value = GatePrompt.SignIn(row, step.reason)
                }
            } finally {
                asking = false
            }
        }
    }

    private fun pass() {
        val action = passed
        passed = null
        _gate.value = null
        action?.invoke()
    }

    /** An entry at the prompt. It stays only as long as the check takes. */
    fun submitPin(pin: String) {
        val prompt = _gate.value as? GatePrompt.Pin ?: return
        if (prompt.checking || prompt.waitMs > 0) return
        _gate.value = prompt.copy(checking = true)
        gateJob = viewModelScope.launch {
            val check = try {
                gate.check(prompt.row.serverId, pin)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing that says why: it is not a question of the PIN, and nothing of it belongs in a log.
                Log.w(TAG, "an entry could not be checked")
                null
            }
            when (check) {
                PinCheck.Correct -> pass()
                is PinCheck.Wrong -> _gate.value = prompt.copy(notice = PinNotice.Wrong(check.triesBeforeWait))
                is PinCheck.Wait -> {
                    _gate.value = prompt.copy(notice = PinNotice.Wait(check.remainingMs), waitMs = check.remainingMs)
                    followWait(prompt.row)
                }
                // Not a PIN any more, and it cannot be told what is: no way past it.
                PinCheck.NoPin -> {
                    passed = null
                    _gate.value = GatePrompt.SignIn(prompt.row, SignInReason.UNREADABLE)
                }
                null -> _gate.value = prompt.copy(notice = PinNotice.CheckFailed)
            }
        }
    }

    /** The time left, read from the store: the screen only shows it. The words stay until it is over, for TalkBack. */
    private fun followWait(row: ProfileRow) {
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            while (true) {
                delay(WAIT_TICK_MS)
                val left = try {
                    gate.waitMs(row.serverId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                _gate.update { p -> (p as? GatePrompt.Pin)?.copy(waitMs = left, notice = if (left > 0) p.notice ?: PinNotice.Wait(left) else PinNotice.Ready) ?: p }
                if (left <= 0) break
            }
        }
    }

    /** What the system's prompt answered. Only a success passes. */
    fun deviceLockAnswered(answer: DeviceLockAnswer) {
        val prompt = _gate.value as? GatePrompt.Device ?: return
        when {
            answer == DeviceLockAnswer.PASSED -> pass()
            prompt.confirmingNew -> {
                passed = null
                _gate.value = null
                // Back to the choice, which now knows better.
                _protection.update { (it as? ProtectionStep.Choose)?.copy(deviceLockAvailable = gate.deviceLockAvailable()) ?: it }
            }
            answer == DeviceLockAnswer.CANCELLED -> cancelGate()
            else -> {
                passed = null
                _gate.value = GatePrompt.SignIn(prompt.row, if (answer == DeviceLockAnswer.NOT_AVAILABLE) SignInReason.DEVICE_LOCK_GONE else SignInReason.DEVICE_LOCK_FAILED)
            }
        }
    }

    fun cancelGate() {
        gateJob?.cancel()
        waitJob?.cancel()
        passed = null
        _gate.value = null
    }

    fun switchTo(row: ProfileRow) {
        if (row.active || operations.running.value != null) return
        throughGate(row) { operations.switchTo(row.serverId, row.name) }
    }

    /** "Entfernen": first the question with what it takes along. The active profile has „Abmelden“ instead. */
    fun askRemove(row: ProfileRow) {
        if (row.active || operations.running.value != null) return
        throughGate(row) {
            _removal.value = RemovalQuestion(row, null)
            factsJob?.cancel()
            factsJob = viewModelScope.launch {
                val facts = readFacts(row.serverId)
                _removal.update { q -> q?.takeIf { it.row.serverId == row.serverId }?.copy(facts = facts) }
            }
        }
    }

    /** „Profil schützen“, for the active profile: what protects it now has to be passed before it can be changed. */
    fun changeProtection() {
        val row = profiles.value?.firstOrNull { it.active } ?: return
        if (operations.running.value != null) return
        throughGate(row) { _protection.value = ProtectionStep.Choose(row, row.protection, gate.deviceLockAvailable()) }
    }

    fun chooseProtection(kind: ProfileLockKind) {
        val choice = _protection.value as? ProtectionStep.Choose ?: return
        val serverId = choice.row.serverId
        when {
            kind == ProfileLockKind.PIN -> _protection.value = ProtectionStep.NewPin(choice.row)
            kind == choice.current -> _protection.value = null
            kind == ProfileLockKind.NONE -> saveProtection(choice.row) { gate.remove(serverId) }
            kind == ProfileLockKind.DEVICE && choice.deviceLockAvailable -> {
                // Seen to work once: a lock that does not answer would keep the person out.
                passed = { saveProtection(choice.row) { gate.setDevice(serverId) } }
                _gate.value = GatePrompt.Device(choice.row, confirmingNew = true)
            }
        }
    }

    /** The entered PIN, twice the same by now. */
    fun setNewPin(pin: String) {
        val step = _protection.value as? ProtectionStep.NewPin ?: return
        if (step.saving || !isValidPin(pin)) return
        _protection.value = step.copy(saving = true)
        saveProtection(step.row) { gate.setPin(step.row.serverId, pin) }
    }

    private fun saveProtection(row: ProfileRow, save: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                save()
                _protection.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "a protection could not be saved")
                _protection.value = ProtectionStep.Choose(row, row.protection, gate.deviceLockAvailable(), saveFailed = true)
            }
        }
    }

    fun cancelProtection() {
        _protection.value = null
    }

    private suspend fun readFacts(serverId: String): RemovalFacts = try {
        removalFacts(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "what a removal takes along is unreadable", e)
        RemovalFacts(0, null, false)
    }

    fun cancelRemove() {
        factsJob?.cancel()
        _removal.value = null
    }

    fun confirmRemove() {
        val question = _removal.value?.takeIf { it.facts != null } ?: return
        _removal.value = null
        operations.remove(question.row.serverId, question.row.name)
    }

    /** The result has been on screen. */
    fun shown(outcome: ProfileOutcome) = operations.shown(outcome)

    private companion object {
        const val TAG = "Profiles"
        const val WAIT_TICK_MS = 1_000L
    }
}
