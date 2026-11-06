package io.wolftown.lauschig.ui.profiles

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.account.PinCheck
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.account.ProfileLocks
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * The one combination `androidx.biometric` supports on every version from Android 9 on with the screen lock as the way in:
 * „device credential“ alone only exists from Android 11, and with a strong biometric not on 9 and 10. There the library
 * shows the system's prompt with its PIN button, or goes to the system's screen lock page when no fingerprint is enrolled.
 */
internal const val DEVICE_LOCK_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/** Whether this device has a lock to confirm with, right now. */
fun interface DeviceLock {
    fun available(): Boolean
}

class AndroidDeviceLock @Inject constructor(@ApplicationContext private val context: Context) : DeviceLock {
    override fun available() = BiometricManager.from(context).canAuthenticate(DEVICE_LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceLockModule {
    @Binds abstract fun deviceLock(impl: AndroidDeviceLock): DeviceLock
}

/** Why the way in is a sign-in to this account (the way of a forgotten PIN). */
enum class SignInReason { DEVICE_LOCK_GONE, DEVICE_LOCK_FAILED, UNREADABLE }

/** What stands before entering or removing a profile. */
sealed interface GateStep {
    data object Open : GateStep
    data class Pin(val waitMs: Long) : GateStep
    data object DeviceLock : GateStep
    data class SignIn(val reason: SignInReason) : GateStep
}

/**
 * The one place that decides what protects a profile (Phase 7, Task 3). It reads the store at the moment it is asked, not what
 * a screen shows; what cannot be read or decided never opens: then the way in is a sign-in.
 */
class ProfileGate @Inject constructor(private val locks: ProfileLocks, private val deviceLock: DeviceLock) {
    /** Only for showing. */
    val kinds: Flow<Map<String, ProfileLockKind>> = locks.kinds.catch { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "the protections cannot be shown")
        emit(emptyMap())
    }

    suspend fun ask(serverId: String): GateStep = try {
        when (locks.kind(serverId)) {
            ProfileLockKind.NONE -> GateStep.Open
            ProfileLockKind.PIN -> GateStep.Pin(locks.waitRemainingMs(serverId))
            // Removed later on: not open, but the way of a forgotten PIN.
            ProfileLockKind.DEVICE -> if (deviceLock.available()) GateStep.DeviceLock else GateStep.SignIn(SignInReason.DEVICE_LOCK_GONE)
            ProfileLockKind.UNREADABLE -> GateStep.SignIn(SignInReason.UNREADABLE)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Log.w(TAG, "a protection cannot be read")
        GateStep.SignIn(SignInReason.UNREADABLE)
    }

    suspend fun check(serverId: String, pin: String): PinCheck = locks.verifyPin(serverId, pin)

    suspend fun waitMs(serverId: String): Long = locks.waitRemainingMs(serverId)

    fun deviceLockAvailable(): Boolean = deviceLock.available()

    suspend fun setPin(serverId: String, pin: String) = locks.setPin(serverId, pin)

    suspend fun setDevice(serverId: String) = locks.setDevice(serverId)

    suspend fun remove(serverId: String) = locks.remove(serverId)

    private companion object {
        const val TAG = "ProfileGate"
    }
}

/** How the device's lock answered. */
enum class DeviceLockAnswer { PASSED, CANCELLED, NOT_AVAILABLE, FAILED }

/** What [BiometricPrompt] reports as an error, and what that is here. Only a success ever passes. */
internal fun deviceLockAnswer(errorCode: Int): DeviceLockAnswer = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT -> DeviceLockAnswer.CANCELLED
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT -> DeviceLockAnswer.NOT_AVAILABLE
    else -> DeviceLockAnswer.FAILED
}

/** For telling what came first: a prompt and an answer. */
private val moments = AtomicLong()

internal fun nextMoment(): Long = moments.incrementAndGet()

/** An answer of the device's lock; [at] tells it from an answer to an earlier question ([DeviceLockPrompter.result]). */
class DeviceLockResult(val answer: DeviceLockAnswer, val at: Long = nextMoment())

/** Shows the system's prompt of the device's lock. */
interface DeviceLockPrompter {
    /** The last answer. It waits here for a screen that is recreated in between. */
    val result: StateFlow<DeviceLockResult?>

    /** Shows the prompt. While one is up nothing more appears, its answer counts. */
    fun show(title: String, subtitle: String)
}

/** The prompter of the activity; none in a test or where there is no such thing: then it counts as not available. */
val LocalDeviceLockPrompter = staticCompositionLocalOf<DeviceLockPrompter?> { null }

internal fun deviceLockPromptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo =
    BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(DEVICE_LOCK_AUTHENTICATORS)
        .build()

/**
 * Made in the activity's `onCreate`, as the library asks: then an answer that comes in after a recreation reaches this one, and
 * waits in [result] until the screen is there again.
 */
class AndroidDeviceLockPrompter(activity: FragmentActivity) : DeviceLockPrompter {
    private val _result = MutableStateFlow<DeviceLockResult?>(null)
    override val result: StateFlow<DeviceLockResult?> = _result.asStateFlow()

    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _result.value = DeviceLockResult(DeviceLockAnswer.PASSED)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                _result.value = DeviceLockResult(deviceLockAnswer(errorCode))
            }
            // onAuthenticationFailed: a finger that did not match. The prompt stays and says so itself.
        },
    )

    override fun show(title: String, subtitle: String) = prompt.authenticate(deviceLockPromptInfo(title, subtitle))
}
