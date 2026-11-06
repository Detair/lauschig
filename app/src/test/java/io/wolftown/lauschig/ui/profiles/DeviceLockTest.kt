package io.wolftown.lauschig.ui.profiles

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Task 3.4, what `androidx.biometric` does on each version (minSdk 28). The library decides by `Build.VERSION.SDK_INT`, so that is
 * set here: one sandbox per Android version would not fit the tests' heap. This checks that the library accepts the request and
 * knows about the lock on Android 9 and 10. The prompt itself is the system's and has not been seen on them (report of Task 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeviceLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val realSdk = Build.VERSION.SDK_INT

    private fun onAndroid(sdk: Int) = ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)

    @After fun tearDown() = onAndroid(realSdk)

    @Test fun thePromptRequest_isValid_onEveryVersion() {
        for (sdk in listOf(28, 29, 30, 31, 35, 37)) {
            onAndroid(sdk)
            val info = deviceLockPromptInfo("Profile kind", "Confirm")
            assertEquals("$sdk", DEVICE_LOCK_AUTHENTICATORS, info.allowedAuthenticators)
            assertEquals("$sdk", "Profile kind", info.title)
        }
    }

    /** Why not the plainer ones: they are refused on Android 9 and 10, by the library's own check. */
    @Test fun theOtherCombinations_areRefusedOnOldAndroid() {
        val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
        for (sdk in listOf(28, 29)) {
            onAndroid(sdk)
            for (combination in listOf(credential, strong or credential)) {
                try {
                    BiometricPrompt.PromptInfo.Builder().setTitle("t").setAllowedAuthenticators(combination).build()
                    fail("android $sdk accepted $combination")
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }

    /** Android 9 and 10: the library asks the keyguard. */
    @Test fun onOldAndroid_aDeviceWithALock_isAvailable_withoutOneItIsNot() {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        for (sdk in listOf(28, 29)) {
            onAndroid(sdk)
            keyguard.setIsDeviceSecure(true)
            assertTrue("$sdk", AndroidDeviceLock(context).available())
            keyguard.setIsDeviceSecure(false)
            assertFalse("$sdk", AndroidDeviceLock(context).available())
        }
    }

    @Test fun onlyASuccessPasses_everyErrorHasItsWay() {
        for (code in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT)) {
            assertEquals("$code", DeviceLockAnswer.CANCELLED, deviceLockAnswer(code))
        }
        for (code in listOf(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT)) {
            assertEquals("$code", DeviceLockAnswer.NOT_AVAILABLE, deviceLockAnswer(code))
        }
        for (code in listOf(BiometricPrompt.ERROR_HW_UNAVAILABLE, BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT, BiometricPrompt.ERROR_VENDOR, 999)) {
            assertEquals("$code", DeviceLockAnswer.FAILED, deviceLockAnswer(code))
        }
    }
}
