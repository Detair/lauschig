package io.wolftown.lauschig.ui.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountData
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.account.displayHost
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
data class ProfileRow(val serverId: String, val name: String, val host: String?, val active: Boolean, val needsReauth: Boolean)

/** What a removal takes along. [unsynced] null: could not be checked. */
data class RemovalFacts(val downloadBytes: Long, val unsynced: Int?, val onMissingCard: Boolean)

/** The question before a removal; [facts] null while they are read. */
data class RemovalQuestion(val row: ProfileRow, val facts: RemovalFacts?)

/** The rows in an order that does not change with the active profile. */
internal fun profileRows(active: Account?, saved: List<Account>, activeNeedsReauth: Boolean, savedNeedingReauth: Set<String>): List<ProfileRow> {
    val all = listOfNotNull(active) + saved
    val severalServers = all.distinctBy { it.displayHost }.size > 1
    return all.map {
        val isActive = it.serverId == active?.serverId
        ProfileRow(it.serverId, it.username, it.displayHost.takeIf { severalServers }, isActive, if (isActive) activeNeedsReauth else it.serverId in savedNeedingReauth)
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
    private val removalFacts: suspend (serverId: String) -> RemovalFacts,
) : ViewModel() {
    @Inject constructor(accounts: AccountStore, operations: ProfileOperations, data: AccountData) : this(
        accounts, operations,
        { RemovalFacts(data.downloadBytes(it), data.unsynced(it), data.downloadsOnMissingVolume(it)) },
    )

    /** Null until the first local read. */
    val profiles: StateFlow<List<ProfileRow>?> =
        combine(accounts.account, accounts.saved, accounts.needsReauth, accounts.savedNeedingReauth, ::profileRows)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val busy: StateFlow<ProfileWork?> = operations.running
    val outcome: StateFlow<ProfileOutcome?> = operations.outcome

    private val _removal = MutableStateFlow<RemovalQuestion?>(null)
    val removal: StateFlow<RemovalQuestion?> = _removal.asStateFlow()
    private var factsJob: Job? = null

    init {
        // It lives as long as the activity, and was asked of the account before. The running operations never let this
        // happen under the open question, a late SSO answer can.
        viewModelScope.launch {
            accounts.account.map { it?.serverId }.distinctUntilChanged().drop(1).collect { cancelRemove() }
        }
    }

    /**
     * Everything that goes into or removes another profile passes here, so here is where the protection of a profile
     * (Phase 7, Task 3: `ProfileGate`) asks first.
     */
    private fun throughGate(@Suppress("UNUSED_PARAMETER") serverId: String, action: () -> Unit) = action()

    fun switchTo(row: ProfileRow) {
        if (row.active) return
        throughGate(row.serverId) { operations.switchTo(row.serverId, row.name) }
    }

    /** "Entfernen": first the question with what it takes along. The active profile has „Abmelden“ instead. */
    fun askRemove(row: ProfileRow) {
        if (row.active || operations.running.value != null) return
        throughGate(row.serverId) {
            _removal.value = RemovalQuestion(row, null)
            factsJob?.cancel()
            factsJob = viewModelScope.launch {
                val facts = readFacts(row.serverId)
                _removal.update { q -> q?.takeIf { it.row.serverId == row.serverId }?.copy(facts = facts) }
            }
        }
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
    }
}
