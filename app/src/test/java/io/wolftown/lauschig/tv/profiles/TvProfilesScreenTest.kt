package io.wolftown.lauschig.tv.profiles

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.PinNotice
import io.wolftown.lauschig.ui.profiles.ProfileOutcome
import io.wolftown.lauschig.ui.profiles.ProfileWork
import io.wolftown.lauschig.ui.profiles.ProfileRow
import io.wolftown.lauschig.ui.profiles.SignInReason
import android.view.KeyEvent
import org.robolectric.shadows.ShadowDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvProfilesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val anna = ProfileRow("aaaa", "Anna", null, active = false, needsReauth = false)
    private val ben = ProfileRow("bbbb", "Ben", null, active = true, needsReauth = false, protection = ProfileLockKind.PIN)
    private val kim = ProfileRow("cccc", "Kim", null, active = false, needsReauth = false)
    private val rows = listOf(anna, ben, kim)

    private val chosen = mutableListOf<String>()
    private val pins = mutableListOf<String>()
    private val signIns = mutableListOf<String>()
    private var cancelled = 0
    private var added = 0

    private val shown = mutableListOf<ProfileOutcome>()

    private fun show(gate: GatePrompt? = null, list: List<ProfileRow> = rows, busy: ProfileWork? = null, outcome: ProfileOutcome? = null) =
        rule.setContent {
            TvTheme {
                TvProfilesScreen(
                    list, gate, onChoose = { chosen += it.name }, onPin = { pins += it }, onCancelGate = { cancelled++ },
                    onAdd = { added++ }, onSignIn = { signIns += it }, busy = busy, outcome = outcome, onOutcomeShown = { shown += it },
                )
            }
        }

    /** Back as the system delivers it when no element takes the key (and on newer Android always). */
    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun outcome(kind: ProfileOutcome.Kind, name: String) = ProfileOutcome(kind, name, 1)

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun pressInDialog(key: Key) {
        rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test fun theFocus_isOnTheActiveProfile() {
        show()
        rule.onNodeWithTag(TAG_PROFILE + ben.serverId).assertIsFocused()
    }

    @Test fun nobodyActive_theFocusIsOnTheFirst() {
        show(list = rows.map { it.copy(active = false) })
        rule.onNodeWithTag(TAG_PROFILE + anna.serverId).assertIsFocused()
    }

    @Test fun ok_choosesTheProfile() {
        show()
        press(Key.DirectionCenter)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf("Ben", "Anna"), chosen)
    }

    @Test fun addProfile_isReachable() {
        show()
        press(Key.DirectionRight, times = 2)
        rule.onNodeWithTag(TAG_PROFILE_ADD).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, added)
    }

    @Test fun aPinPrompt_showsThePad_forThatProfile() {
        show(GatePrompt.Pin(ben))
        rule.onNodeWithTag(TAG_PIN_TITLE).assertTextEquals(context.getString(R.string.pin_title, "Ben"))
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
        rule.onNodeWithTag(TAG_PROFILE + ben.serverId).assertDoesNotExist()
        press(Key.One)
        press(Key.Two)
        press(Key.Three)
        press(Key.Four)
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("1234"), pins)
    }

    @Test fun aWrongPin_saysSo_andBackCancels() {
        show(GatePrompt.Pin(ben, notice = PinNotice.Wrong(4)))
        rule.onNodeWithTag(TAG_PIN_NOTICE).assertContentDescriptionEquals(context.resources.getQuantityString(R.plurals.pin_wrong, 4, 4))
        back()
        assertEquals(1, cancelled)
    }

    @Test fun aForgottenPin_leadsToTheSignIn() {
        show(GatePrompt.Pin(ben))
        press(Key.DirectionLeft, times = 2) // 5, 4, then off the pad
        rule.onNodeWithTag(TAG_PIN_FORGOT).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(ben.serverId), signIns)
    }

    @Test fun backFromThePin_theFocusIsOnTheProfileAgain() {
        var gate by mutableStateOf<GatePrompt?>(null)
        rule.setContent {
            TvTheme {
                TvProfilesScreen(
                    rows, gate, onChoose = { gate = GatePrompt.Pin(it) }, onPin = {}, onCancelGate = { gate = null },
                    onAdd = {}, onSignIn = {},
                )
            }
        }
        press(Key.DirectionRight) // Kim
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_PIN_TITLE).assertTextEquals(context.getString(R.string.pin_title, "Kim"))
        back()
        rule.onNodeWithTag(TAG_PROFILE + kim.serverId).assertIsFocused()
    }

    @Test fun aProfileBehindTheDeviceLock_offersTheSignIn() {
        show(GatePrompt.Device(anna))
        rule.onNodeWithText(context.getString(R.string.tv_profiles_device_lock, "Anna")).assertExists()
        rule.onNodeWithTag(TAG_SIGN_IN).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf(anna.serverId), signIns)
    }

    @Test fun theSignInQuestion_canBeCancelled() {
        show(GatePrompt.SignIn(anna, SignInReason.UNREADABLE))
        rule.onNodeWithText(context.getString(R.string.profiles_signin_unreadable, "Anna")).assertExists()
        pressInDialog(Key.DirectionLeft)
        rule.onNodeWithTag(TAG_SIGN_IN_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(1, cancelled)
        assertEquals(emptyList<String>(), signIns)
    }

    @Test fun back_withTheFocusOnForgotPin_cancelsThePin_notThePage() {
        show(GatePrompt.Pin(ben))
        press(Key.DirectionLeft, times = 2)
        rule.onNodeWithTag(TAG_PIN_FORGOT).assertIsFocused()
        back()
        assertEquals(1, cancelled)
        assertEquals(emptyList<String>(), signIns)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun back_inTheSignInDialog_cancelsIt() {
        show(GatePrompt.SignIn(anna, SignInReason.DEVICE_LOCK_FAILED))
        rule.onNodeWithTag(TAG_SIGN_IN).assertIsFocused()
        // The remote's Back as the dialog's own window gets it.
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(emptyList<String>(), signIns)
    }

    @Test fun aRunningSwitch_blocksThePage_andHoldsTheFocus() {
        show(busy = ProfileWork.Switching("Kim"))
        rule.onNodeWithText(context.getString(R.string.profiles_switching, "Kim")).assertIsFocused()
        rule.onNodeWithTag(TAG_PROFILE + ben.serverId).assertDoesNotExist()
        press(Key.DirectionCenter)
        back()
        assertEquals(emptyList<String>(), chosen)
        assertEquals(0, cancelled)
        assertFalse(rule.activity.isFinishing)
    }

    @Test fun aFailedSwitch_saysSo_andOkTakesItOff() {
        val failed = outcome(ProfileOutcome.Kind.SWITCH_FAILED, "Kim")
        show(outcome = failed)
        rule.onNodeWithText(context.getString(R.string.profiles_switch_failed, "Kim")).assertExists()
        rule.onNodeWithTag(TAG_OUTCOME_OK).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf(failed), shown)
    }

    @Test fun aSwitchThatWorked_isNotShownHere() {
        show(outcome = outcome(ProfileOutcome.Kind.SWITCHED, "Kim"))
        rule.onNodeWithTag(TAG_OUTCOME_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag(TAG_PROFILE + ben.serverId).assertIsFocused()
    }
}
