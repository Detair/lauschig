package io.wolftown.lauschig.account

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** How a profile is protected. [UNREADABLE]: it is, but what with cannot be read any more; that never opens it. */
enum class ProfileLockKind { NONE, DEVICE, PIN, UNREADABLE }

/** What a PIN entry came to. */
sealed interface PinCheck {
    data object Correct : PinCheck

    /** Wrong, and [triesBeforeWait] more can be wrong before the wait starts. */
    data class Wrong(val triesBeforeWait: Int) : PinCheck

    /** Nothing was checked, or this entry was wrong and [started] the wait: [remainingMs] to go. */
    data class Wait(val remainingMs: Long, val started: Boolean) : PinCheck

    /** This profile has no readable PIN (any more). */
    data object NoPin : PinCheck
}

/**
 * The rounds for a new hash (3.1: at least 120 000, under 500 ms on the slow tablet). May change any time: each hash keeps
 * the count it was made with, so nobody's PIN stops working.
 */
const val PIN_ITERATIONS = 120_000

/** The digits of an app PIN. */
val PIN_LENGTHS = 4..8

/** Wrong entries without any wait; the last of them starts it. */
const val PIN_FREE_TRIES = 5

private const val FIRST_WAIT_MS = 30_000L
private const val LONGEST_WAIT_MS = 15 * 60_000L

/** Only ASCII digits: one PIN on every keyboard, and the same bytes for the hash on every version. */
fun isValidPin(pin: String): Boolean = pin.length in PIN_LENGTHS && pin.all { it in '0'..'9' }

/** The wait [failures] wrong entries in a row come to: from the [PIN_FREE_TRIES]th on 30 s, then twice as long each, 15 min at most. */
fun pinWaitMs(failures: Int): Long {
    if (failures < PIN_FREE_TRIES) return 0
    val doublings = (failures - PIN_FREE_TRIES).coerceAtMost(LONGEST_DOUBLINGS)
    return (FIRST_WAIT_MS shl doublings).coerceAtMost(LONGEST_WAIT_MS)
}

private const val LONGEST_DOUBLINGS = 5

/**
 * What protects each profile on this device, per server id. Everything about it is encrypted with the [TokenCipher] of the
 * tokens and lives with the account data, which has no backup (`allowBackup="false"`). Nothing of it is ever logged. It is
 * meant to keep children from switching, not to stop someone with full access to the device.
 *
 * Whatever cannot be read counts as protected ([ProfileLockKind.UNREADABLE]): the way in is then a sign-in, never no way at all.
 */
interface ProfileLocks {
    /** The profiles with a protection. Only for showing: a decision reads [kind]. */
    val kinds: Flow<Map<String, ProfileLockKind>>

    suspend fun kind(serverId: String): ProfileLockKind

    /** A new app PIN, with no count. A [pin] that is not [isValidPin] is refused with an [IllegalArgumentException]. */
    suspend fun setPin(serverId: String, pin: String)

    suspend fun setDevice(serverId: String)

    /** No protection any more, nothing left of it. */
    suspend fun remove(serverId: String)

    /**
     * Checks an entry against the app PIN, one at a time. During a wait nothing is checked. The entry counts as wrong before
     * it is hashed and is taken back only when it turned out right, so a process that dies in between has spent a try. A
     * failure to store that is thrown: then nothing was checked.
     */
    suspend fun verifyPin(serverId: String, pin: String): PinCheck

    suspend fun waitRemainingMs(serverId: String): Long
}

private val DAMAGED = booleanPreferencesKey("damaged")

/** A damaged file must not take the protections with it: it is replaced by a mark that counts for every profile without an entry. */
internal val damagedLocksHandler = ReplaceFileCorruptionHandler { preferencesOf(DAMAGED to true) }

private val Context.profileLocksDataStore by preferencesDataStore(name = "profile_locks", corruptionHandler = damagedLocksHandler)

/**
 * The wait runs on [elapsedMs], the time since boot, which no setting of the clock changes. After a reboot it starts again
 * and is no longer comparable: then only what has certainly passed counts ([remaining]). So a wait can get longer, never
 * shorter.
 */
@Singleton
class DataStoreProfileLocks(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val iterations: Int = PIN_ITERATIONS,
    private val derive: (CharArray, ByteArray, Int) -> ByteArray = ::pbkdf2,
) : ProfileLocks {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher) : this(context.profileLocksDataStore, cipher)

    /** An encrypted entry's contents; its text never shows them. */
    @Serializable
    private data class Entry(
        val kind: String,
        val salt: String? = null,
        val hash: String? = null,
        val iterations: Int = 0,
        val failures: Int = 0,
        val waitStartedAt: Long = 0,
        val waitMs: Long = 0,
    ) {
        override fun toString() = "Entry($kind)"
    }

    private val checks = Mutex()

    override val kinds: Flow<Map<String, ProfileLockKind>> = store.data
        .map { p ->
            p.asMap().keys.map { it.name }.filter { it.startsWith(PREFIX) }
                .associate { it.removePrefix(PREFIX) to kindOf(p, it.removePrefix(PREFIX)) }
                .filterValues { it != ProfileLockKind.NONE }
        }
        .distinctUntilChanged()
        // The first use of the cipher reads the keystore.
        .flowOn(Dispatchers.Default)

    override suspend fun kind(serverId: String): ProfileLockKind {
        val p = store.data.first()
        return withContext(Dispatchers.Default) { kindOf(p, serverId) }
    }

    private fun kindOf(p: Preferences, serverId: String): ProfileLockKind {
        val raw = p[key(serverId)] ?: return if (p[DAMAGED] == true) ProfileLockKind.UNREADABLE else ProfileLockKind.NONE
        val entry = entryOf(raw) ?: return ProfileLockKind.UNREADABLE
        return when {
            entry.kind == KIND_NONE -> ProfileLockKind.NONE
            entry.kind == KIND_DEVICE -> ProfileLockKind.DEVICE
            entry.isPin() -> ProfileLockKind.PIN
            else -> ProfileLockKind.UNREADABLE
        }
    }

    private fun Entry.isPin() = kind == KIND_PIN && salt != null && hash != null && iterations > 0

    /** Null: unreadable. What went wrong is not logged: a parser's message quotes what it parsed. */
    private fun entryOf(raw: String): Entry? = try {
        cipher.decrypt(raw)?.let { json.decodeFromString<Entry>(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun setPin(serverId: String, pin: String) {
        require(isValidPin(pin)) { "not a PIN" }
        val entry = withContext(Dispatchers.Default) {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            Entry(KIND_PIN, encoded(salt), encoded(hash(pin, salt, iterations)), iterations)
        }
        checks.withLock { store.edit { it.put(serverId, entry) } }
    }

    override suspend fun setDevice(serverId: String) {
        checks.withLock { store.edit { it.put(serverId, Entry(KIND_DEVICE)) } }
    }

    override suspend fun remove(serverId: String) {
        checks.withLock {
            store.edit {
                // Over a damaged file "no entry" would mean protected.
                if (it[DAMAGED] == true) it.put(serverId, Entry(KIND_NONE)) else it.remove(key(serverId))
            }
        }
    }

    override suspend fun verifyPin(serverId: String, pin: String): PinCheck = withContext(NonCancellable + Dispatchers.Default) {
        checks.withLock {
            val entry = store.data.first()[key(serverId)]?.let(::entryOf)?.takeIf { it.isPin() } ?: return@withLock PinCheck.NoPin
            val left = remaining(entry)
            if (left > 0) return@withLock PinCheck.Wait(left, started = false)
            val failures = entry.failures + 1
            val waitMs = pinWaitMs(failures)
            store.edit { it.put(serverId, entry.copy(failures = failures, waitStartedAt = elapsedMs(), waitMs = waitMs)) }
            if (MessageDigest.isEqual(hash(pin, decoded(entry.salt!!), entry.iterations), decoded(entry.hash!!))) {
                store.edit { it.put(serverId, entry.copy(failures = 0, waitStartedAt = 0, waitMs = 0)) }
                PinCheck.Correct
            } else if (waitMs > 0) {
                PinCheck.Wait(waitMs, started = true)
            } else {
                PinCheck.Wrong(PIN_FREE_TRIES - failures)
            }
        }
    }

    override suspend fun waitRemainingMs(serverId: String): Long {
        val p = store.data.first()
        return withContext(Dispatchers.Default) { p[key(serverId)]?.let(::entryOf)?.takeIf { it.isPin() }?.let(::remaining) ?: 0 }
    }

    /**
     * What is left of the wait. A time since boot below the start means a reboot, and at least that time has passed since. A
     * reboot that is not recognised (long up by now) makes the difference smaller than what has really passed.
     */
    private fun remaining(e: Entry): Long {
        if (e.waitMs <= 0) return 0
        val now = elapsedMs()
        val passed = if (now >= e.waitStartedAt) now - e.waitStartedAt else now
        return (e.waitMs - passed).coerceAtLeast(0)
    }

    private fun MutablePreferences.put(serverId: String, entry: Entry) {
        this[key(serverId)] = cipher.encrypt(json.encodeToString(entry))
    }

    private fun hash(pin: String, salt: ByteArray, rounds: Int): ByteArray {
        val chars = pin.toCharArray()
        val started = System.nanoTime()
        try {
            return derive(chars, salt, rounds)
        } finally {
            chars.fill(' ')
            // For measuring on the slow tablet. Rounds and time, nothing about the PIN.
            Log.i(TAG, "hash: $rounds iterations, ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms")
        }
    }

    private companion object {
        const val TAG = "ProfileLocks"
        const val PREFIX = "lock_"
        const val KIND_NONE = "NONE"
        const val KIND_DEVICE = "DEVICE"
        const val KIND_PIN = "PIN"
        const val SALT_BYTES = 16
        const val HASH_BITS = 256
        val random = SecureRandom()

        /** Later versions may add to an entry. */
        val json = Json { ignoreUnknownKeys = true }

        fun key(serverId: String) = stringPreferencesKey(PREFIX + serverId)
        fun encoded(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun decoded(text: String): ByteArray = Base64.getDecoder().decode(text)

        fun pbkdf2(pin: CharArray, salt: ByteArray, rounds: Int): ByteArray {
            val spec = PBEKeySpec(pin, salt, rounds, HASH_BITS)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileLocksModule {
    @Binds abstract fun locks(impl: DataStoreProfileLocks): ProfileLocks
}
