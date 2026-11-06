package io.wolftown.lauschig.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** The store without the hash: what the screens and the gate see. The real one is `ProfileLocksTest`'s. */
class FakeProfileLocks : ProfileLocks {
    val all = MutableStateFlow<Map<String, ProfileLockKind>>(emptyMap())
    val pins = mutableMapOf<String, String>()
    val waits = mutableMapOf<String, Long>()
    val checked = mutableListOf<String>()
    private var failures = 0

    /** A check that has not finished yet. */
    var hold: CompletableDeferred<Unit>? = null
    var failReading = false
    var failWriting = false
    var failChecking = false

    override val kinds: Flow<Map<String, ProfileLockKind>> = all

    override suspend fun kind(serverId: String): ProfileLockKind {
        if (failReading) throw IOException("unreadable")
        return all.value[serverId] ?: ProfileLockKind.NONE
    }

    override suspend fun setPin(serverId: String, pin: String) {
        require(isValidPin(pin))
        hold?.await()
        if (failWriting) throw IOException("disk full")
        pins[serverId] = pin
        all.value += serverId to ProfileLockKind.PIN
    }

    override suspend fun setDevice(serverId: String) {
        if (failWriting) throw IOException("disk full")
        pins.remove(serverId)
        all.value += serverId to ProfileLockKind.DEVICE
    }

    override suspend fun remove(serverId: String) {
        if (failWriting) throw IOException("disk full")
        pins.remove(serverId)
        waits.remove(serverId)
        all.value -= serverId
    }

    val signedIn = mutableListOf<String>()

    override suspend fun accountSignedIn(serverId: String) {
        signedIn += serverId
    }

    override suspend fun verifyPin(serverId: String, pin: String): PinCheck {
        hold?.await()
        if (failChecking) throw IOException("disk full")
        val pinned = pins[serverId] ?: return PinCheck.NoPin
        waits[serverId]?.takeIf { it > 0 }?.let { return PinCheck.Wait(it, started = false) }
        checked += serverId
        if (pin == pinned) {
            failures = 0
            return PinCheck.Correct
        }
        failures++
        val wait = pinWaitMs(failures)
        if (wait > 0) {
            waits[serverId] = wait
            return PinCheck.Wait(wait, started = true)
        }
        return PinCheck.Wrong(PIN_FREE_TRIES - failures)
    }

    override suspend fun waitRemainingMs(serverId: String): Long {
        if (failReading) throw IOException("unreadable")
        return waits[serverId] ?: 0
    }

    fun pin(serverId: String, pin: String = "1234") {
        pins[serverId] = pin
        all.value += serverId to ProfileLockKind.PIN
    }

    fun device(serverId: String) {
        all.value += serverId to ProfileLockKind.DEVICE
    }

    fun unreadable(serverId: String) {
        all.value += serverId to ProfileLockKind.UNREADABLE
    }
}
