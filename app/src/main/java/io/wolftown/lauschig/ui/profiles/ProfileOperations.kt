package io.wolftown.lauschig.ui.profiles

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.ProfileSwitch
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What is being done with the profiles right now; the app says so and starts nothing else until it is done. */
sealed interface ProfileWork {
    val name: String
    data class Switching(override val name: String) : ProfileWork
    data class Removing(override val name: String) : ProfileWork
}

/** How an operation ended; waits here until the app has shown it. Every result is its own, also the same one twice. */
data class ProfileOutcome(val kind: Kind, val name: String, private val seq: Long) {
    enum class Kind { SWITCHED, SWITCH_FAILED, REMOVED, REMOVE_FAILED }
}

/**
 * The profile operations of the screens. A switch can take some 20 s at worst (push, cancelling the work, two stops), far
 * longer than a screen can be relied on: the device turns, the activity goes, the pages of the account being left are
 * torn down in the middle. So they run here, off the main thread, for the whole process: [running] and [outcome] are
 * there for whichever screen looks, however often it was recreated, and one operation at a time.
 */
@Singleton
class ProfileOperations(private val profiles: ProfileSwitch, private val scope: CoroutineScope) {
    @Inject constructor(profiles: ProfileSwitch) : this(profiles, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _running = MutableStateFlow<ProfileWork?>(null)
    val running: StateFlow<ProfileWork?> = _running.asStateFlow()

    private val _outcome = MutableStateFlow<ProfileOutcome?>(null)
    val outcome: StateFlow<ProfileOutcome?> = _outcome.asStateFlow()

    private val seq = AtomicLong()

    /** Switches to the saved profile [serverId]; false, doing nothing, while another operation runs. */
    fun switchTo(serverId: String, name: String): Boolean = start(ProfileWork.Switching(name)) {
        finished(if (succeeds { profiles.switchProfile(serverId) }) ProfileOutcome.Kind.SWITCHED else ProfileOutcome.Kind.SWITCH_FAILED, name)
    }

    /** Removes the saved profile [serverId]; false, doing nothing, while another operation runs. */
    fun remove(serverId: String, name: String): Boolean = start(ProfileWork.Removing(name)) {
        finished(if (succeeds { profiles.removeProfile(serverId) }) ProfileOutcome.Kind.REMOVED else ProfileOutcome.Kind.REMOVE_FAILED, name)
    }

    /**
     * Sets the active account aside for the newly signed-in [next], after any operation still running (the sign-in has
     * happened, it must not get lost). The caller may go away meanwhile: the switch still arrives. A failure is thrown to
     * the caller, which still has its form open; the active account stays.
     */
    suspend fun add(next: Account, accessToken: String, refreshToken: String) {
        scope.async {
            val work = ProfileWork.Switching(next.username)
            while (!_running.compareAndSet(null, work)) _running.first { it == null }
            try {
                profiles.addProfile(next, accessToken, refreshToken)
                finished(ProfileOutcome.Kind.SWITCHED, next.username)
            } finally {
                _running.value = null
            }
        }.await()
    }

    /** The result has been on screen; a newer one stays. */
    fun shown(outcome: ProfileOutcome) {
        _outcome.compareAndSet(outcome, null)
    }

    private fun start(work: ProfileWork, block: suspend () -> Unit): Boolean {
        if (!_running.compareAndSet(null, work)) return false
        scope.launch {
            try {
                block()
            } finally {
                _running.value = null
            }
        }
        return true
    }

    private suspend fun succeeds(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "profile operation failed", e)
        false
    }

    private fun finished(kind: ProfileOutcome.Kind, name: String) {
        _outcome.value = ProfileOutcome(kind, name, seq.incrementAndGet())
    }

    private companion object {
        const val TAG = "ProfileOperations"
    }
}
