package io.wolftown.lauschig.ui.profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.FakeProfileLocks
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.account.ProfileSwitch
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.ui.LauschigTheme
import io.wolftown.lauschig.ui.common.accessibilityInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 3 on screen: the dialogs, and each way to them with the real view model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class ProfileGateScreensTest {
    @get:Rule val rule = createComposeRule()
    private val row = ProfileRow("bbbbbbbbbbbbbbbb", "kind", null, active = false, needsReauth = false)
    private val calls = mutableListOf<String>()
    private val field get() = rule.onNodeWithTag("pinField")

    /** The PIN dialogs in the page: see [LocalPinDialogFrame]. Everything else is as it is in the app. */
    private val inPage = PinDialogFrame { _, tag, title, text, confirm, dismiss ->
        Column(Modifier.testTag(tag)) {
            title()
            text()
            confirm()
            dismiss()
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalPinDialogFrame provides inPage) { LauschigTheme(content = content) }
    }

    private fun enteredText() = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun showPrompt(prompt: GatePrompt.Pin) = rule.setContent {
        Themed { PinPromptDialog(prompt, onSubmit = { calls += "pin:$it" }, onForgot = { calls += "forgot" }, onCancel = { calls += "cancel" }) }
    }

    // --- The prompt ---

    @Test fun thePrompt_saysForWhom_andOnlyAPinCanBeSent() {
        showPrompt(GatePrompt.Pin(row))
        rule.onNodeWithText("PIN for kind").assertExists()
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
        field.performTextInput("123")
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
        field.performTextInput("4")
        rule.onNodeWithTag("pinOk").assertIsEnabled().performClick()
        assertEquals(listOf("pin:1234"), calls)
    }

    @Test fun theField_takesDigits_atMostEight_isNotShown_andTalkBackKnows() {
        showPrompt(GatePrompt.Pin(row))
        field.performTextInput("12a3 4567890")
        assertEquals("what is shown, and read out, is never the digits", "••••••••", enteredText())
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test fun anEntry_isGoneFromTheFieldOnceSent() {
        showPrompt(GatePrompt.Pin(row))
        field.performTextInput("1234")
        rule.onNodeWithTag("pinOk").performClick()
        assertEquals("", enteredText())
    }

    @Test fun theKeyboardsDone_sendsToo_butNotTooShort() {
        showPrompt(GatePrompt.Pin(row))
        field.performTextInput("12")
        field.performImeAction()
        assertTrue(calls.isEmpty())
        field.performTextInput("34")
        field.performImeAction()
        assertEquals(listOf("pin:1234"), calls)
    }

    /** Whatever was entered is gone with a recreation: nothing of it is saved, and the prompt starts empty. */
    @Test fun aRotation_clearsTheEntry() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Themed { PinPromptDialog(GatePrompt.Pin(row), {}, {}, {}) } }
        field.performTextInput("1234")
        tester.emulateSavedInstanceStateRestore()
        assertEquals("", enteredText())
    }

    @Test fun whileItChecks_nothingIsSentTwice() {
        showPrompt(GatePrompt.Pin(row, checking = true))
        field.performTextInput("1234")
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
        field.performImeAction()
        assertTrue(calls.isEmpty())
    }

    /** I1 of Task 2: the words come on a node that was there before. */
    @Test fun whatItSays_isALiveRegion_onTheSameNode_beforeAndAfter() {
        var prompt by mutableStateOf(GatePrompt.Pin(row))
        rule.setContent { Themed { PinPromptDialog(prompt, {}, {}, {}) } }
        val line = rule.onNodeWithTag("pinNotice", useUnmergedTree = true)
        val before = line.fetchSemanticsNode().id
        assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE, line.accessibilityInfo()!!.liveRegion)
        assertFalse(SemanticsProperties.ContentDescription in line.fetchSemanticsNode().config)
        for ((next, words) in listOf(
            PinNotice.Wrong(4) to "Wrong PIN. 4 more tries before a wait.",
            PinNotice.Wrong(1) to "Wrong PIN. One more try before a wait.",
            PinNotice.Wait(30_000) to "Too many wrong entries. Please wait 30 seconds.",
            PinNotice.Wait(900_000) to "Too many wrong entries. Please wait 15 minutes.",
            PinNotice.Wait(60_000) to "Too many wrong entries. Please wait 1 minute.",
            PinNotice.Ready to "You can try again now.",
            PinNotice.CheckFailed to "The entry could not be checked. Please try again.",
        )) {
            prompt = prompt.copy(notice = next)
            rule.waitForIdle()
            assertEquals(before, line.fetchSemanticsNode().id)
            assertEquals(listOf(words), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        }
    }

    @Test fun duringAWait_nothingCanBeEntered_itCountsDown_andForgottenStaysThere() {
        showPrompt(GatePrompt.Pin(row, notice = PinNotice.Wait(30_000), waitMs = 27_500))
        field.assertIsNotEnabled()
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
        rule.onNodeWithText("0:28 left").assertExists()
        rule.onNodeWithTag("pinForgot").assertIsEnabled().performClick()
        assertEquals(listOf("forgot"), calls)
    }

    @Test fun theCountdown() {
        assertEquals("0:01", pinCountdown(1))
        assertEquals("0:30", pinCountdown(30_000))
        assertEquals("15:00", pinCountdown(900_000))
        assertEquals("1:00", pinCountdown(59_500))
    }

    // --- A new PIN ---

    private fun showNewPin(saving: Boolean = false) = rule.setContent {
        Themed { NewPinDialog(ProtectionStep.NewPin(row, saving), onDone = { calls += "new:$it" }, onCancel = { calls += "cancel" }) }
    }

    @Test fun aNewPin_twiceTheSame_isDone() {
        showNewPin()
        rule.onNodeWithText("4 to 8 digits").assertExists()
        field.performTextInput("5678")
        rule.onNodeWithText("Next").performClick()
        assertEquals("", enteredText())
        assertTrue(calls.isEmpty())
        field.performTextInput("5678")
        rule.onNodeWithText("OK").performClick()
        assertEquals(listOf("new:5678"), calls)
    }

    @Test fun aNewPin_tooShort_goesNowhere() {
        showNewPin()
        field.performTextInput("567")
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
        field.performImeAction()
        rule.onNodeWithText("Enter the PIN again.").assertDoesNotExist()
    }

    @Test fun aNewPin_twoDifferent_startsOver_saidOnTheSameLine() {
        showNewPin()
        val line = rule.onNodeWithTag("pinNotice", useUnmergedTree = true)
        val before = line.fetchSemanticsNode().id
        field.performTextInput("5678")
        rule.onNodeWithTag("pinOk").performClick()
        assertEquals(listOf("Enter the PIN again."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(before, line.fetchSemanticsNode().id)
        field.performTextInput("5679")
        rule.onNodeWithTag("pinOk").performClick()
        assertEquals(listOf("The two entries were different. Please start again."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(before, line.fetchSemanticsNode().id)
        assertTrue(calls.isEmpty())
        rule.onNodeWithText("Next").assertExists()
        field.performTextInput("1111")
        rule.onNodeWithTag("pinOk").performClick()
        field.performTextInput("1111")
        rule.onNodeWithTag("pinOk").performClick()
        assertEquals(listOf("new:1111"), calls)
    }

    @Test fun aNewPin_aRotation_startsOver_nothingIsSaved() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Themed { NewPinDialog(ProtectionStep.NewPin(row), onDone = { calls += "new:$it" }, onCancel = {}) } }
        field.performTextInput("5678")
        rule.onNodeWithTag("pinOk").performClick()
        field.performTextInput("56")
        tester.emulateSavedInstanceStateRestore()
        assertEquals("", enteredText())
        rule.onNodeWithText("Next").assertExists()
        rule.onNodeWithText("Enter the PIN again.").assertDoesNotExist()
    }

    @Test fun aNewPin_whileItIsSaved_nothingIsSentAgain() {
        showNewPin(saving = true)
        field.performTextInput("1234")
        rule.onNodeWithTag("pinOk").assertIsNotEnabled()
    }

    // --- The choice ---

    private fun showChoice(current: ProfileLockKind = ProfileLockKind.NONE, device: Boolean = true) = rule.setContent {
        Themed { ProtectionChoiceDialog(ProtectionStep.Choose(row, current, device), onChoose = { calls += "$it" }, onCancel = { calls += "cancel" }) }
    }

    @Test fun theChoice_threeWays_theCurrentOneMarked_andWhatItIsFor() {
        showChoice(ProfileLockKind.PIN)
        rule.onNodeWithTag("protection:PIN").assertIsSelected()
        rule.onNodeWithTag("protection:NONE").performClick()
        rule.onNodeWithTag("protection:DEVICE").performClick()
        assertEquals(listOf("NONE", "DEVICE"), calls)
        rule.onNodeWithText("This keeps children from switching to this profile. It does not protect against someone with full access to the device.").assertExists()
    }

    /** The review's C1 and M3: what the protection does not hold against is said where it is chosen. */
    @Test fun theChoice_saysWhoStillGetsIn() {
        showChoice()
        rule.onNodeWithText("With SSO the app asks for a fresh login. A provider may ignore that, and a password saved in the browser can be filled in.").assertExists()
        rule.onNodeWithText("Anyone who can unlock the device gets in here too.").assertExists()
    }

    @Test fun theChoice_withoutADeviceLock_greyed_withAHint() {
        showChoice(device = false)
        rule.onNodeWithTag("protection:DEVICE").assertIsNotEnabled().performClick()
        assertTrue(calls.isEmpty())
        rule.onNodeWithText("This device has no screen lock.").assertExists()
        rule.onNodeWithText("Anyone who can unlock the device gets in here too.").assertDoesNotExist()
    }

    /** I1 of Task 2: on the node that was there before. */
    @Test fun theChoice_whatCouldNotBeSaved_isSaid_onTheLineThatWasThere() {
        var step by mutableStateOf(ProtectionStep.Choose(row, ProfileLockKind.NONE, deviceLockAvailable = true))
        rule.setContent { Themed { ProtectionChoiceDialog(step, onChoose = {}, onCancel = {}) } }
        val line = rule.onNodeWithTag("protectionError", useUnmergedTree = true)
        val before = line.fetchSemanticsNode().id
        assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE, line.accessibilityInfo()!!.liveRegion)
        assertFalse(SemanticsProperties.ContentDescription in line.fetchSemanticsNode().config)
        step = step.copy(saveFailed = true)
        rule.waitForIdle()
        assertEquals(before, line.fetchSemanticsNode().id)
        assertEquals(listOf("The protection could not be saved. Nothing has changed."), line.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    // --- A sign-in instead ---

    @Test fun aSignInInstead_saysWhy() {
        var reason by mutableStateOf(SignInReason.DEVICE_LOCK_GONE)
        rule.setContent {
            Themed { SignInInsteadDialog(GatePrompt.SignIn(row, reason), onSignIn = { calls += "signIn" }, onCancel = {}) }
        }
        rule.onNodeWithText("kind is protected by the device lock, but this device no longer has one. Sign in with this account and the protection is removed.").assertExists()
        reason = SignInReason.DEVICE_LOCK_FAILED
        rule.onNodeWithText("The device lock did not work just now. Try again later, or sign in as kind and the protection is removed.").assertExists()
        reason = SignInReason.UNREADABLE
        rule.onNodeWithText("The protection of kind can no longer be read. Sign in with this account and it is removed.").assertExists()
        rule.onNodeWithTag("signInInstead").performClick()
        assertEquals(listOf("signIn"), calls)
    }

    // --- The system's prompt ---

    private inner class FakePrompter : DeviceLockPrompter {
        val answers = MutableStateFlow<DeviceLockResult?>(null)
        override val result: StateFlow<DeviceLockResult?> = answers
        val shown = mutableListOf<String>()
        override fun show(title: String, subtitle: String) { shown += "$title/$subtitle" }
    }

    private fun showStep(prompter: DeviceLockPrompter?, prompt: GatePrompt.Device) = rule.setContent {
        CompositionLocalProvider(LocalDeviceLockPrompter provides prompter) {
            Themed { DeviceLockStep(prompt) { calls += "$it" } }
        }
    }

    @Test fun theDeviceLock_isShown_andItsAnswerTaken() {
        val prompter = FakePrompter()
        showStep(prompter, GatePrompt.Device(row))
        rule.waitForIdle()
        assertEquals(listOf("Profile kind/Confirm with your screen lock"), prompter.shown)
        assertTrue(calls.isEmpty())
        prompter.answers.value = DeviceLockResult(DeviceLockAnswer.PASSED)
        rule.waitForIdle()
        assertEquals(listOf("PASSED"), calls)
    }

    /** A success that came for an earlier question, and was left in the prompter: this question is still open. */
    @Test fun anAnswerFromBeforeTheQuestion_doesNotCount() {
        val prompter = FakePrompter()
        prompter.answers.value = DeviceLockResult(DeviceLockAnswer.PASSED)
        showStep(prompter, GatePrompt.Device(row))
        rule.waitForIdle()
        assertTrue(calls.isEmpty())
        assertEquals("it is asked", 1, prompter.shown.size)
    }

    /** The activity was recreated while the system's prompt was up, and the answer came first: it is not asked again. */
    @Test fun anAnswerFromAfterTheQuestion_isTaken_andItIsNotAskedAgain() {
        val prompter = FakePrompter()
        val prompt = GatePrompt.Device(row)
        prompter.answers.value = DeviceLockResult(DeviceLockAnswer.PASSED)
        showStep(prompter, prompt)
        rule.waitForIdle()
        assertEquals(listOf("PASSED"), calls)
        assertTrue(prompter.shown.isEmpty())
    }

    @Test fun noPrompter_notAvailable_notOpen() {
        showStep(null, GatePrompt.Device(row))
        rule.waitForIdle()
        assertEquals(listOf("NOT_AVAILABLE"), calls)
    }

    // --- Each way in, through the real view model ---

    private val family = ServerAddresses(null, "https://abs.example.org".toHttpUrl())
    private val fabian = Account("aaaaaaaaaaaaaaaa", "u1", "Fabian", true, family, emptySet())
    private val kind = Account("bbbbbbbbbbbbbbbb", "u2", "kind", false, family, emptySet())
    private val store = FakeAccountStore()
    private val locks = FakeProfileLocks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val viewModels = TestViewModels()
    private val switched = mutableListOf<String>()
    private val ops = ProfileOperations(object : ProfileSwitch {
        override suspend fun switchProfile(serverId: String): Boolean { switched += serverId; return store.activate(serverId) }
        override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) = store.add(next, accessToken, refreshToken)
        override suspend fun removeProfile(serverId: String): Boolean { switched += "removed:$serverId"; return store.forget(serverId) }
    }, scope)

    @After fun tearDown() {
        viewModels.clear()
        scope.cancel()
    }

    private fun vm(): ProfilesViewModel {
        store.acc.value = fabian
        store.aside.value = listOf(FakeAccountStore.Aside(kind, "a", "r", false))
        locks.pin(kind.serverId)
        return viewModels.own(ProfilesViewModel(store, ops, ProfileGate(locks) { true }) { RemovalFacts(1, 0, false) })
    }

    private fun passThePrompt() {
        rule.onNodeWithTag("pinPrompt").assertExists()
        assertTrue("nothing before the gate", switched.isEmpty())
        field.performTextInput("1234")
        rule.onNodeWithTag("pinOk").performClick()
        rule.waitForIdle()
    }

    private fun theSheet(open: Boolean, vm: ProfilesViewModel, opened: (() -> Unit)? = null) {
        rule.setContent {
            Themed {
                var isOpen by androidx.compose.runtime.remember { mutableStateOf(open) }
                val rows by vm.profiles.collectAsState()
                if (opened != null) ProfileHeaderButton(rows, onClick = { isOpen = true })
                ProfilesHost(vm, isOpen, onClose = { isOpen = false }, onAdd = { calls += "add" }, onSignIn = { calls += "signIn:$it" })
            }
        }
    }

    /** From the settings row: `AppRoot` opens the same host. */
    @Test fun theSheet_asksBeforeASwitch() {
        theSheet(open = true, vm())
        rule.onNodeWithTag("profile:kind").performClick()
        passThePrompt()
        assertEquals(listOf(kind.serverId), switched)
    }

    @Test fun theHeaderAvatarsSheet_asksBeforeASwitch() {
        theSheet(open = false, vm()) {}
        rule.onNodeWithContentDescription("Profiles, active: Fabian").performClick()
        rule.onNodeWithTag("profile:kind").performClick()
        passThePrompt()
        assertEquals(listOf(kind.serverId), switched)
    }

    @Test fun theSheet_stopsBehindAPrompt_andACancelIsBackInIt() {
        theSheet(open = true, vm())
        rule.onNodeWithTag("profile:kind").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("pinPrompt").assertDoesNotExist()
        rule.onNodeWithTag("profilesSheet").assertExists()
        assertTrue(switched.isEmpty())
    }

    @Test fun theSheet_asksBeforeRemoving() {
        theSheet(open = true, vm())
        rule.onNodeWithContentDescription("More options for kind").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithTag("removeDialog").assertDoesNotExist()
        passThePrompt()
        rule.onNodeWithTag("removeDialog").assertExists()
        assertTrue(switched.isEmpty())
    }

    @Test fun theSheet_aForgottenPin_leadsToThatAccountsForm() {
        theSheet(open = true, vm())
        rule.onNodeWithTag("profile:kind").performClick()
        rule.onNodeWithTag("pinForgot").performClick()
        rule.onNodeWithTag("pinPrompt").assertDoesNotExist()
        assertEquals(listOf("signIn:${kind.serverId}"), calls)
        assertTrue(switched.isEmpty())
    }

    @Test fun theSheet_asksBeforeChangingAProtection() {
        val vm = vm()
        locks.pin(fabian.serverId, "4321")
        theSheet(open = true, vm)
        rule.onNodeWithTag("protectProfile").performClick()
        rule.onNodeWithTag("protectionChoice").assertDoesNotExist()
        field.performTextInput("4321")
        rule.onNodeWithTag("pinOk").performClick()
        rule.onNodeWithTag("protectionChoice").assertExists()
    }

    @Test fun theSheet_turnsAProtectionOnInTheEnd() {
        val vm = vm()
        theSheet(open = true, vm)
        rule.onNodeWithTag("protectProfile").performClick()
        rule.onNodeWithTag("protection:PIN").performClick()
        field.performTextInput("5678")
        rule.onNodeWithTag("pinOk").performClick()
        field.performTextInput("5678")
        rule.onNodeWithTag("pinOk").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("newPin").assertDoesNotExist()
        assertEquals("5678", locks.pins[fabian.serverId])
    }

    @Test fun thePicker_asks_andItsForgottenPin_leadsToThatAccountsForm() {
        val vm = vm()
        store.acc.value = null
        store.aside.value = listOf(FakeAccountStore.Aside(fabian, "a", "r", false), FakeAccountStore.Aside(kind, "a", "r", false))
        rule.setContent {
            Themed { ProfilePickerRoute(onAdd = {}, onNoneLeft = {}, onSignIn = { calls += "signIn:$it" }, viewModel = vm) }
        }
        rule.onNodeWithTag("profile:kind").performClick()
        rule.onNodeWithTag("pinPrompt").assertExists()
        assertTrue(switched.isEmpty())
        rule.onNodeWithTag("pinForgot").performClick()
        assertEquals(listOf("signIn:${kind.serverId}"), calls)
    }

    @Test fun thePicker_goesInAfterThePrompt() {
        val vm = vm()
        store.acc.value = null
        store.aside.value = listOf(FakeAccountStore.Aside(fabian, "a", "r", false), FakeAccountStore.Aside(kind, "a", "r", false))
        rule.setContent {
            Themed { ProfilePickerRoute(onAdd = {}, onNoneLeft = {}, onSignIn = {}, viewModel = vm) }
        }
        rule.onNodeWithTag("profile:kind").performClick()
        passThePrompt()
        assertEquals(listOf(kind.serverId), switched)
    }

}
