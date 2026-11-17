package io.wolftown.lauschig.tv.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.settings.SignOutStep
import io.wolftown.lauschig.tv.TV_QUALIFIERS
import io.wolftown.lauschig.tv.TvTheme
import io.wolftown.lauschig.tv.profiles.TAG_PIN_DIGIT
import io.wolftown.lauschig.tv.profiles.TAG_PIN_NOTICE
import io.wolftown.lauschig.tv.profiles.TAG_PIN_TITLE
import io.wolftown.lauschig.ui.profiles.GatePrompt
import io.wolftown.lauschig.ui.profiles.ProfileRow
import io.wolftown.lauschig.ui.profiles.ProtectionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = TV_QUALIFIERS)
class TvSettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val base = TvSettingsState(
        profileName = "Anna", host = "abs.example.org", online = true, checking = false, protection = ProfileLockKind.NONE,
        moreProfiles = true, screenOffMinutes = 5, versionName = "1.2.3", versionCode = 42,
    )
    private val anna = ProfileRow("aaaa", "Anna", null, active = true, needsReauth = false)

    private val calls = mutableListOf<String>()
    private val pins = mutableListOf<String>()
    private val gatePins = mutableListOf<String>()
    private val screenOff = mutableListOf<Int>()

    private var state by mutableStateOf(base)
    private var signOut by mutableStateOf<SignOutStep?>(null)
    private var gate by mutableStateOf<GatePrompt?>(null)
    private var step by mutableStateOf<ProtectionStep?>(null)

    private fun show() = rule.setContent {
        TvTheme {
            TvSettingsScreen(
                state, signOut,
                onSwitchProfile = { calls += "switch" },
                onSetPin = { pins += it },
                onRemovePin = { calls += "removePin" },
                onScreenOff = { screenOff += it },
                onReconnect = { calls += "reconnect" },
                onSignOut = { calls += "signOut" },
                onConfirmSignOut = { calls += "confirmSignOut" },
                onCancelSignOut = { calls += "cancelSignOut" },
                gate = gate,
                protectionStep = step,
                onProtect = { calls += "protect" },
                onChoosePin = { calls += "choosePin" },
                onGatePin = { gatePins += it },
                onSignIn = { calls += "signIn:$it" },
                onCancelProtection = { calls += "cancelProtection" },
            )
        }
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun pressInDialog(key: Key, times: Int = 1) = repeat(times) {
        rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun row(key: String) = rule.onNodeWithTag(TAG_SETTINGS_ROW + key)

    @Test fun theFocusStartsOnTheFirstRow_andEveryRowIsReachable() {
        show()
        row(ROW_PROFILE).assertIsFocused()
        listOf(ROW_PROTECT, ROW_SCREEN_OFF, ROW_CONNECTION, ROW_SIGN_OUT, ROW_ABOUT).forEach {
            press(Key.DirectionDown)
            row(it).assertIsFocused()
        }
        // The end of the list holds the focus.
        press(Key.DirectionDown)
        row(ROW_ABOUT).assertIsFocused()
    }

    @Test fun ok_onEachRow_triggersIt() {
        show()
        press(Key.DirectionCenter) // switch
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // protect
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter) // reconnect
        press(Key.DirectionDown)
        press(Key.DirectionCenter) // sign out
        assertEquals(listOf("switch", "protect", "reconnect", "signOut"), calls)
    }

    @Test fun oneProfileWithoutProtection_hasNoProtectionRow_butCanSwitch() {
        state = base.copy(moreProfiles = false)
        show()
        row(ROW_PROTECT).assertDoesNotExist()
        row(ROW_PROFILE).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        row(ROW_SCREEN_OFF).assertIsFocused()
        assertEquals(listOf("switch"), calls)
    }

    @Test fun oneProfileWithAPin_keepsTheRow_soItCanBeTakenOff() {
        state = base.copy(moreProfiles = false, protection = ProfileLockKind.PIN)
        show()
        row(ROW_PROTECT).assertExists()
        rule.onNodeWithText(context.getString(R.string.profiles_protect_pin)).assertExists()
    }

    @Test fun theRowsSayWhatApplies() {
        state = base.copy(online = false)
        show()
        rule.onNodeWithText(context.getString(R.string.tv_settings_profile_active, "Anna")).assertExists()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.tv_settings_screen_off_minutes, 5, 5)).assertExists()
        rule.onNodeWithText(context.getString(R.string.tv_settings_connection_line, "abs.example.org", context.getString(R.string.connection_state_offline))).assertExists()
        rule.onNodeWithText(context.getString(R.string.connection_reconnect)).assertExists()
        rule.onNodeWithText(context.getString(R.string.tv_settings_version, "1.2.3", 42)).assertExists()
        state = base.copy(checking = true)
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.tv_settings_connection_line, "abs.example.org", context.getString(R.string.connection_state_checking))).assertExists()
    }

    @Test fun screenOff_opensOnTheCurrentChoice_andOkSetsTheMinutes() {
        show()
        row(ROW_PROFILE).assertIsFocused()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        rule.onNodeWithTag(TAG_SCREEN_OFF_OPTION + 5).assertIsFocused()
        pressInDialog(Key.DirectionDown)
        rule.onNodeWithTag(TAG_SCREEN_OFF_OPTION + 15).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf(15), screenOff)
        rule.onNodeWithTag(TAG_SCREEN_OFF_DIALOG).assertDoesNotExist()
        // Back on the row it came from.
        row(ROW_SCREEN_OFF).assertIsFocused()
    }

    @Test fun screenOff_never_isZero() {
        show()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        pressInDialog(Key.DirectionUp, times = 2)
        rule.onNodeWithTag(TAG_SCREEN_OFF_OPTION + 0).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf(0), screenOff)
    }

    @Test fun signOut_onlyAfterTheQuestion_andTheFocusStartsOnCancel() {
        show()
        press(Key.DirectionDown, times = 4)
        press(Key.DirectionCenter)
        assertEquals(listOf("signOut"), calls)
        signOut = SignOutStep.Confirm(unsynced = 0, downloadBytes = 0)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_SIGN_OUT_CANCEL).assertIsFocused()
        rule.onNodeWithTag(TAG_SIGN_OUT_UNSYNCED).assertDoesNotExist()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf("signOut", "cancelSignOut"), calls)
    }

    @Test fun signOut_confirmed() {
        signOut = SignOutStep.Confirm(unsynced = 0, downloadBytes = 0)
        show()
        rule.onNodeWithTag(TAG_SIGN_OUT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionRight)
        rule.onNodeWithTag(TAG_SIGN_OUT_CONFIRM).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf("confirmSignOut"), calls)
    }

    @Test fun signOut_namesTheProgressNotOnTheServerYet() {
        signOut = SignOutStep.Confirm(unsynced = 3, downloadBytes = 0)
        show()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.sign_out_unsynced_kept, 3, 3)).assertExists()
        rule.onNodeWithText(context.getString(R.string.sign_out_message, "abs.example.org")).assertExists()
        // The TV has no downloads to keep: no checkbox about them.
        rule.onAllNodesWithText(context.getString(R.string.sign_out_keep_data)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test fun signOut_anUnknownCount_isSaidToBeUnknown() {
        signOut = SignOutStep.Confirm(unsynced = null, downloadBytes = 0)
        show()
        rule.onNodeWithText(context.getString(R.string.sign_out_unsynced_unknown)).assertExists()
    }

    @Test fun signOut_whileUploading_confirmIsOff() {
        signOut = SignOutStep.Checking
        show()
        rule.onNodeWithText(context.getString(R.string.sign_out_syncing)).assertExists()
        rule.onNodeWithTag(TAG_SIGN_OUT_CONFIRM).assertExists()
        rule.onNodeWithTag(TAG_SIGN_OUT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionRight)
        pressInDialog(Key.DirectionCenter)
        assertFalse("confirmSignOut" in calls)
    }

    @Test fun protect_theChoice_setsOrRemovesThePin() {
        step = ProtectionStep.Choose(anna.copy(protection = ProfileLockKind.PIN), ProfileLockKind.PIN, deviceLockAvailable = true)
        show()
        rule.onNodeWithTag(TAG_PROTECT_SET).assertIsFocused()
        rule.onNodeWithText(context.getString(R.string.tv_settings_change_pin)).assertExists()
        // The device lock is not offered on a TV (spec E12).
        rule.onAllNodesWithText(context.getString(R.string.profiles_protect_device)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        pressInDialog(Key.DirectionCenter)
        pressInDialog(Key.DirectionRight)
        rule.onNodeWithTag(TAG_PROTECT_REMOVE).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf("choosePin", "removePin"), calls)
    }

    @Test fun protect_withoutAPin_offersNoRemoval() {
        step = ProtectionStep.Choose(anna, ProfileLockKind.NONE, deviceLockAvailable = false)
        show()
        rule.onNodeWithText(context.getString(R.string.tv_settings_set_pin)).assertExists()
        rule.onNodeWithTag(TAG_PROTECT_REMOVE).assertDoesNotExist()
        pressInDialog(Key.DirectionRight)
        rule.onNodeWithTag(TAG_PROTECT_CANCEL).assertIsFocused()
        pressInDialog(Key.DirectionCenter)
        assertEquals(listOf("cancelProtection"), calls)
    }

    @Test fun aNewPin_needsTwoEqualEntries() {
        step = ProtectionStep.NewPin(anna)
        show()
        rule.onNodeWithTag(TAG_NEW_PIN_TITLE).assertExists()
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
        rule.onNodeWithTag(TAG_NEW_PIN_STEP).assertContentDescriptionEquals(context.getString(R.string.pin_new_hint))
        typeAndOk("1234")
        assertEquals(emptyList<String>(), pins)
        rule.onNodeWithTag(TAG_NEW_PIN_STEP).assertContentDescriptionEquals(context.getString(R.string.pin_new_again))
        typeAndOk("1235")
        assertEquals(emptyList<String>(), pins)
        rule.onNodeWithTag(TAG_PIN_NOTICE).assertContentDescriptionEquals(context.getString(R.string.pin_new_differs))
        rule.onNodeWithTag(TAG_NEW_PIN_STEP).assertContentDescriptionEquals(context.getString(R.string.pin_new_hint))
        typeAndOk("4321")
        typeAndOk("4321")
        assertEquals(listOf("4321"), pins)
    }

    @Test fun aNewPin_isNeverOnScreenOrReadOut() {
        step = ProtectionStep.NewPin(anna)
        show()
        listOf(Key.One, Key.Two, Key.Three, Key.Four).forEach(::press)
        val texts = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
            fun all(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(n) + n.children.flatMap(::all)
            all(root).flatMap { n ->
                (n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }) +
                    n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }
        }
        assertFalse(texts.any { "1234" in it })
    }

    @Test fun theCurrentPin_isAskedOnItsOwnPage_andItsForgottenWayIsTheSignIn() {
        gate = GatePrompt.Pin(anna)
        show()
        rule.onNodeWithTag(TAG_PIN_TITLE).assertExists()
        row(ROW_PROFILE).assertDoesNotExist()
        typeAndOk("2468")
        assertEquals(listOf("2468"), gatePins)
        gate = null
        rule.waitForIdle()
        // Back on the list, on the row it came from (here the first, nothing moved before).
        row(ROW_PROFILE).assertIsFocused()
    }

    @Test fun backFromThePinPage_returnsToTheProtectionRow() {
        show()
        press(Key.DirectionDown)
        row(ROW_PROTECT).assertIsFocused()
        gate = GatePrompt.Pin(anna)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf("cancelProtection"), calls)
        gate = null
        rule.waitForIdle()
        row(ROW_PROTECT).assertIsFocused()
    }

    @Test fun theProtectionRowGoing_leavesTheFocusOnTheRowInItsPlace() {
        state = base.copy(moreProfiles = false, protection = ProfileLockKind.PIN)
        show()
        press(Key.DirectionDown)
        row(ROW_PROTECT).assertIsFocused()
        state = base.copy(moreProfiles = false, protection = ProfileLockKind.NONE)
        rule.waitForIdle()
        row(ROW_SCREEN_OFF).assertIsFocused()
    }

    private fun typeAndOk(pin: String) {
        pin.forEach { d -> press(digitKey(d)) }
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        // The pad puts the focus back on the 5 for the next entry.
        rule.onNodeWithTag(TAG_PIN_DIGIT + '5').assertIsFocused()
    }

    private fun digitKey(d: Char): Key = when (d) {
        '0' -> Key.Zero
        '1' -> Key.One
        '2' -> Key.Two
        '3' -> Key.Three
        '4' -> Key.Four
        '5' -> Key.Five
        '6' -> Key.Six
        '7' -> Key.Seven
        '8' -> Key.Eight
        else -> Key.Nine
    }
}
