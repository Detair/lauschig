package io.wolftown.lauschig.ui.profiles

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.DataStoreProfileLocks
import io.wolftown.lauschig.account.FakeProfileLocks
import io.wolftown.lauschig.account.ProfileLockKind
import io.wolftown.lauschig.account.ProfileLocks
import io.wolftown.lauschig.account.ProfileSwitch
import io.wolftown.lauschig.account.TokenCipher
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 3, 3.3 and review focus 3: every way into or out of a protected profile, through the view model the sheet and the picker use. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log
@Config(sdk = [35], application = android.app.Application::class)
class ProfileGateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val family = ServerAddresses(null, "https://abs.example.org".toHttpUrl())
    private val fabian = Account("aaaaaaaaaaaaaaaa", "u1", "Fabian", true, family, emptySet())
    private val kind = Account("bbbbbbbbbbbbbbbb", "u2", "kind", false, family, emptySet())
    private val oma = Account("cccccccccccccccc", "u3", "Oma", false, family, emptySet())

    private val store = FakeAccountStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private inner class FakeProfiles : ProfileSwitch {
        val calls = mutableListOf<String>()
        override suspend fun switchProfile(serverId: String): Boolean { calls += "switch:$serverId"; return store.activate(serverId) }
        override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) = store.add(next, accessToken, refreshToken)
        override suspend fun removeProfile(serverId: String): Boolean { calls += "remove:$serverId"; return store.forget(serverId) }
    }

    private val profiles = FakeProfiles()
    private val ops = ProfileOperations(profiles, scope)
    private val locks = FakeProfileLocks()
    private var deviceLock = true
    private val viewModels = TestViewModels()
    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(main)
        store.acc.value = fabian
        store.aside.value = listOf(kind, oma).map { FakeAccountStore.Aside(it, "acc", "ref", false) }
    }

    @After fun tearDown() {
        viewModels.clear()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun vm(with: ProfileLocks = locks) = viewModels.own(ProfilesViewModel(store, ops, ProfileGate(with) { deviceLock }) { RemovalFacts(1, 0, false) })

    private fun ProfilesViewModel.row(name: String) = profiles.value!!.single { it.name == name }
    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }
    private fun passedTheSwitch(vm: ProfilesViewModel) = await { vm.outcome.filterNotNull().first() }.kind == ProfileOutcome.Kind.SWITCHED

    // --- Into a profile ---

    @Test fun unprotected_noQuestion_itSwitches() {
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertNull(vm.gatePrompt.value)
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
    }

    @Test fun aPin_isAskedBeforeTheSwitch_andOnlyTheRightOneSwitches() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertEquals("kind", (vm.gatePrompt.value as GatePrompt.Pin).row.name)
        assertTrue("nothing before the gate", profiles.calls.isEmpty())
        assertNull(vm.busy.value)

        vm.submitPin("9999")
        assertEquals(PinNotice.Wrong(4), (vm.gatePrompt.value as GatePrompt.Pin).notice)
        assertTrue(profiles.calls.isEmpty())
        assertEquals(fabian, store.acc.value)

        vm.submitPin("1234")
        assertNull(vm.gatePrompt.value)
        assertTrue(passedTheSwitch(vm))
        assertEquals(kind, store.acc.value)
    }

    @Test fun aPin_cancelled_changesNothing_andAsksAgainNextTime() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.cancelGate()
        assertNull(vm.gatePrompt.value)
        vm.submitPin("1234")
        assertTrue(profiles.calls.isEmpty())
        vm.switchTo(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Pin)
    }

    @Test fun leaving_aProtectedProfile_asksNothing() {
        locks.pin(fabian.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertNull(vm.gatePrompt.value)
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
    }

    /** It reads the store at the moment of the tap, not what the row was showing. */
    @Test fun aProtectionTheRowDoesNotKnowYet_stillAsks() {
        val vm = vm()
        val row = vm.row("kind")
        assertEquals(ProfileLockKind.NONE, row.protection)
        locks.pin(kind.serverId)
        vm.switchTo(row)
        assertTrue(vm.gatePrompt.value is GatePrompt.Pin)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun whileAPromptIsUp_nothingElseStarts() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.switchTo(vm.row("Oma"))
        vm.askRemove(vm.row("Oma"))
        vm.changeProtection()
        assertEquals("kind", vm.gatePrompt.value!!.row.name)
        assertNull(vm.removal.value)
        assertNull(vm.protection.value)
        assertTrue(profiles.calls.isEmpty())
    }

    /** The first answer of the store is still out and a second tap comes. */
    @Test fun aSecondTapBeforeTheStoreAnswered_startsNothing() {
        val answer = CompletableDeferred<Unit>()
        val slow = object : ProfileLocks by locks {
            override suspend fun kind(serverId: String): ProfileLockKind {
                answer.await()
                return locks.kind(serverId)
            }
        }
        val vm = vm(slow)
        vm.switchTo(vm.row("kind"))
        vm.switchTo(vm.row("Oma"))
        answer.complete(Unit)
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
    }

    @Test fun whatRuns_keepsTheGateShut() {
        locks.pin(kind.serverId)
        val running = CompletableDeferred<Unit>()
        val busyOps = ProfileOperations(object : ProfileSwitch by profiles {
            override suspend fun switchProfile(serverId: String): Boolean { running.await(); return false }
        }, scope)
        val vm = viewModels.own(ProfilesViewModel(store, busyOps, ProfileGate(locks) { deviceLock }) { RemovalFacts(1, 0, false) })
        vm.switchTo(vm.row("Oma"))
        assertEquals(ProfileWork.Switching("Oma"), vm.busy.value)
        vm.switchTo(vm.row("kind"))
        assertNull("no prompt over a running switch", vm.gatePrompt.value)
        running.complete(Unit)
    }

    @Test fun theDeviceLock_passes_thenItSwitches() {
        locks.device(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Device)
        assertTrue(profiles.calls.isEmpty())
        vm.deviceLockAnswered(DeviceLockAnswer.PASSED)
        assertNull(vm.gatePrompt.value)
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
    }

    @Test fun theDeviceLock_cancelled_changesNothing() {
        locks.device(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.deviceLockAnswered(DeviceLockAnswer.CANCELLED)
        assertNull(vm.gatePrompt.value)
        assertTrue(profiles.calls.isEmpty())
    }

    /** 3.4: removed later on: not open, but the way of a forgotten PIN. */
    @Test fun theDeviceLockIsGone_notOpen_butASignIn() {
        locks.device(kind.serverId)
        deviceLock = false
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertEquals(SignInReason.DEVICE_LOCK_GONE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun theDeviceLockGoesWhileItIsAsked_orFails_aSignIn_notOpen() {
        locks.device(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.deviceLockAnswered(DeviceLockAnswer.NOT_AVAILABLE)
        assertEquals(SignInReason.DEVICE_LOCK_GONE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        vm.cancelGate()
        vm.switchTo(vm.row("kind"))
        vm.deviceLockAnswered(DeviceLockAnswer.FAILED)
        assertEquals(SignInReason.DEVICE_LOCK_FAILED, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun anAnswerWithoutAQuestion_doesNothing() {
        val vm = vm()
        vm.deviceLockAnswered(DeviceLockAnswer.PASSED)
        locks.pin(kind.serverId)
        vm.switchTo(vm.row("kind"))
        vm.deviceLockAnswered(DeviceLockAnswer.PASSED)
        assertTrue("a PIN is not answered by the device lock", vm.gatePrompt.value is GatePrompt.Pin)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun whatCannotBeRead_isNoWayIn() {
        locks.unreadable(kind.serverId)
        locks.pin(oma.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertEquals(SignInReason.UNREADABLE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        vm.cancelGate()
        locks.failReading = true
        vm.switchTo(vm.row("Oma"))
        assertEquals(SignInReason.UNREADABLE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun aPinThatIsNoLongerThere_isNoWayIn() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        locks.pins.clear()
        vm.submitPin("1234")
        assertEquals(SignInReason.UNREADABLE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun aCheckThatFails_isNoWayIn_andSaysSo() {
        locks.pin(kind.serverId)
        locks.failChecking = true
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.submitPin("1234")
        assertEquals(PinNotice.CheckFailed, (vm.gatePrompt.value as GatePrompt.Pin).notice)
        assertFalse((vm.gatePrompt.value as GatePrompt.Pin).checking)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun whileItChecks_itSaysSo_andASecondEntryIsNotChecked() {
        locks.pin(kind.serverId)
        val hold = CompletableDeferred<Unit>()
        locks.hold = hold
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.submitPin("9999")
        assertTrue((vm.gatePrompt.value as GatePrompt.Pin).checking)
        vm.submitPin("9999")
        hold.complete(Unit)
        assertEquals("one entry, one check", 1, locks.checked.size)
        assertFalse((vm.gatePrompt.value as GatePrompt.Pin).checking)
    }

    @Test fun aCancelDuringTheCheck_wins_aLateRightAnswerOpensNothing() {
        locks.pin(kind.serverId)
        val hold = CompletableDeferred<Unit>()
        val slow = object : ProfileLocks by locks {
            // As the real one: the check goes on to its end, whoever went away.
            override suspend fun verifyPin(serverId: String, pin: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                hold.await()
                locks.verifyPin(serverId, pin)
            }
        }
        val vm = vm(slow)
        vm.switchTo(vm.row("kind"))
        vm.submitPin("1234")
        vm.cancelGate()
        hold.complete(Unit)
        assertNull(vm.gatePrompt.value)
        assertEquals(1, locks.checked.size)
        assertTrue(profiles.calls.isEmpty())
        assertNull(vm.busy.value)
    }

    /** A late SSO answer changes the account under the open prompt: nobody answers it for the next pages. */
    @Test fun thePromptGoes_whenTheActiveAccountChanges() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        val row = vm.row("Oma")
        runBlocking { store.activate(row.serverId) }
        assertNull(vm.gatePrompt.value)
        vm.submitPin("1234")
        assertEquals(oma, store.acc.value)
        assertTrue(profiles.calls.isEmpty())
    }

    // --- 3.5, the wait ---

    @Test fun theFifthWrongOne_startsTheWait_nothingCanBeEntered_thenItSaysItIsOver() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        repeat(4) { vm.submitPin("9999") }
        assertEquals(PinNotice.Wrong(1), (vm.gatePrompt.value as GatePrompt.Pin).notice)
        vm.submitPin("9999")
        val waiting = vm.gatePrompt.value as GatePrompt.Pin
        assertEquals(PinNotice.Wait(30_000), waiting.notice)
        assertEquals(30_000L, waiting.waitMs)

        val before = locks.checked.size
        vm.submitPin("1234")
        assertEquals("not even looked at", before, locks.checked.size)
        assertTrue(profiles.calls.isEmpty())

        locks.waits[kind.serverId] = 12_000
        main.scheduler.advanceTimeBy(1_100)
        assertEquals(12_000L, (vm.gatePrompt.value as GatePrompt.Pin).waitMs)
        assertEquals("the words stay: a line that changes every second would talk all the time", PinNotice.Wait(30_000), (vm.gatePrompt.value as GatePrompt.Pin).notice)

        locks.waits[kind.serverId] = 0
        main.scheduler.advanceTimeBy(1_100)
        val over = vm.gatePrompt.value as GatePrompt.Pin
        assertEquals(0L, over.waitMs)
        assertEquals(PinNotice.Ready, over.notice)

        vm.submitPin("1234")
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
    }

    @Test fun cancellingAndAskingAgain_doesNotEndTheWait() {
        locks.pin(kind.serverId)
        locks.waits[kind.serverId] = 20_000
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertEquals(20_000L, (vm.gatePrompt.value as GatePrompt.Pin).waitMs)
        vm.cancelGate()
        vm.switchTo(vm.row("kind"))
        assertEquals(20_000L, (vm.gatePrompt.value as GatePrompt.Pin).waitMs)
        vm.cancelGate()
        locks.waits[kind.serverId] = 0
        main.scheduler.advanceTimeBy(5_000)
        assertNull("the cancelled prompt's clock has ended with it", vm.gatePrompt.value)
    }

    /**
     * The review's M9: a prompt that opens during a wait starts without words, and they come one step later: only a node that was
     * there before and changes is read out (Task 2's I1).
     */
    @Test fun aPromptOpenedDuringAWait_saysSoOneStepLater() {
        locks.pin(kind.serverId)
        locks.waits[kind.serverId] = 20_000
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        val opened = vm.gatePrompt.value as GatePrompt.Pin
        assertEquals(20_000L, opened.waitMs)
        assertNull(opened.notice)
        locks.waits[kind.serverId] = 19_000
        main.scheduler.advanceTimeBy(1_100)
        assertEquals(PinNotice.Wait(19_000), (vm.gatePrompt.value as GatePrompt.Pin).notice)
        locks.waits[kind.serverId] = 18_000
        main.scheduler.advanceTimeBy(1_100)
        assertEquals("then they stay", PinNotice.Wait(19_000), (vm.gatePrompt.value as GatePrompt.Pin).notice)
    }

    // --- Review focus 3, process death ---

    private val cipher = object : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("enc:")
    }
    private val data by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.root.resolve("locks.preferences_pb") } }
    private var uptime = 500_000L
    private fun realLocks() = DataStoreProfileLocks(data, cipher, { uptime }, 1_000)

    private fun aPromptAnswered(vm: ProfilesViewModel, pin: String, until: () -> Boolean) {
        vm.submitPin(pin)
        runBlocking {
            withTimeout(5_000) {
                while (!until()) kotlinx.coroutines.delay(5)
            }
        }
    }

    private fun aRealPrompt(vm: ProfilesViewModel) {
        runBlocking { withTimeout(5_000) { vm.profiles.filterNotNull().first() } }
        vm.switchTo(vm.row("kind"))
        runBlocking { withTimeout(5_000) { vm.gatePrompt.filterNotNull().first() } }
    }

    /** The prompt is in memory only, and the switch comes after it: a new process has the account it had, and no way past it. */
    @Test fun aProcessDeathDuringThePrompt_leavesTheCurrentAccount_andTheCount() {
        runBlocking { realLocks().setPin(kind.serverId, "1234") }
        val first = vm(realLocks())
        aRealPrompt(first)
        var n = 0
        repeat(3) {
            aPromptAnswered(first, "9999") { ((first.gatePrompt.value as? GatePrompt.Pin)?.notice as? PinNotice.Wrong)?.triesBeforeWait == 4 - n }
            n++
        }
        assertEquals(fabian, store.acc.value)
        assertTrue(profiles.calls.isEmpty())

        // The process is gone: only the stores are left.
        val second = vm(realLocks())
        assertNull(second.gatePrompt.value)
        assertEquals(fabian, store.acc.value)
        aRealPrompt(second)
        aPromptAnswered(second, "9999") { (second.gatePrompt.value as? GatePrompt.Pin)?.notice == PinNotice.Wrong(1) }
        aPromptAnswered(second, "9999") { (second.gatePrompt.value as? GatePrompt.Pin)?.notice == PinNotice.Wait(30_000) }

        // Again, during the wait: it goes on, also with the right PIN.
        uptime += 10_000
        val third = vm(realLocks())
        aRealPrompt(third)
        assertEquals(20_000L, (third.gatePrompt.value as GatePrompt.Pin).waitMs)
        third.submitPin("1234")
        Thread.sleep(50)
        assertTrue(profiles.calls.isEmpty())
        assertEquals(fabian, store.acc.value)
    }

    // --- Removing ---

    @Test fun removing_aProtectedProfile_asksFirst_thenTheQuestion_thenItGoes() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.askRemove(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Pin)
        assertNull("not even the question before the gate", vm.removal.value)

        vm.submitPin("9999")
        assertNull(vm.removal.value)
        vm.confirmRemove()
        assertTrue(profiles.calls.isEmpty())

        vm.submitPin("1234")
        assertNull(vm.gatePrompt.value)
        assertEquals("kind", vm.removal.value!!.row.name)
        assertTrue("the question still has to be answered", profiles.calls.isEmpty())
        vm.confirmRemove()
        assertEquals(listOf("remove:${kind.serverId}"), profiles.calls)
    }

    @Test fun removing_cancelledAtTheGate_changesNothing() {
        locks.device(kind.serverId)
        val vm = vm()
        vm.askRemove(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Device)
        vm.deviceLockAnswered(DeviceLockAnswer.CANCELLED)
        vm.confirmRemove()
        assertNull(vm.removal.value)
        assertTrue(profiles.calls.isEmpty())
        assertEquals(2, store.aside.value.size)
    }

    @Test fun removing_withTheDeviceLockGone_aSignIn_notOpen() {
        locks.device(kind.serverId)
        deviceLock = false
        val vm = vm()
        vm.askRemove(vm.row("kind"))
        assertEquals(SignInReason.DEVICE_LOCK_GONE, (vm.gatePrompt.value as GatePrompt.SignIn).reason)
        assertNull(vm.removal.value)
    }

    // --- The picker: nobody active ---

    @Test fun thePicker_asks_too_andNothingIsActive_untilItPassed() {
        store.acc.value = null
        store.aside.value = listOf(fabian, kind).map { FakeAccountStore.Aside(it, "acc", "ref", false) }
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Pin)
        vm.submitPin("9999")
        assertNull(store.acc.value)
        vm.submitPin("1234")
        assertTrue(passedTheSwitch(vm))
        assertEquals(kind, store.acc.value)
    }

    @Test fun thePicker_removing_asksToo() {
        store.acc.value = null
        store.aside.value = listOf(fabian, kind).map { FakeAccountStore.Aside(it, "acc", "ref", false) }
        locks.device(kind.serverId)
        val vm = vm()
        vm.askRemove(vm.row("kind"))
        assertTrue(vm.gatePrompt.value is GatePrompt.Device)
        assertNull(vm.removal.value)
    }

    @Test fun thePicker_withAnUnprotectedProfile_goesInDirectly() {
        store.acc.value = null
        store.aside.value = listOf(fabian, kind).map { FakeAccountStore.Aside(it, "acc", "ref", false) }
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("Fabian"))
        assertNull(vm.gatePrompt.value)
        assertTrue(passedTheSwitch(vm))
    }

    // --- 3.2, the setting ---

    @Test fun theRows_sayWhatIsProtected() {
        locks.pin(kind.serverId)
        locks.device(fabian.serverId)
        val vm = vm()
        assertEquals(ProfileLockKind.PIN, vm.row("kind").protection)
        assertEquals(ProfileLockKind.DEVICE, vm.row("Fabian").protection)
        assertEquals(ProfileLockKind.NONE, vm.row("Oma").protection)
    }

    /** Whoever has one profile never notices: nothing to switch from. */
    @Test fun theSettingsRow_onlyWithSomethingToSwitchFrom_orToTurnOff() {
        store.aside.value = emptyList()
        val vm = vm()
        assertNull(protectionRow(vm.profiles.value))
        locks.pin(fabian.serverId)
        assertEquals("Fabian", protectionRow(vm.profiles.value)!!.name)
        runBlocking { locks.remove(fabian.serverId) }
        store.aside.value = listOf(FakeAccountStore.Aside(kind, "acc", "ref", false))
        assertEquals("Fabian", protectionRow(vm.profiles.value)!!.name)
        store.acc.value = null
        assertNull("for the active profile only", protectionRow(vm.profiles.value))
    }

    @Test fun changing_unprotected_opensTheChoice_withWhatTheDeviceHas() {
        val vm = vm()
        vm.changeProtection()
        assertNull(vm.gatePrompt.value)
        assertEquals(ProtectionStep.Choose(vm.row("Fabian"), ProfileLockKind.NONE, deviceLockAvailable = true), vm.protection.value)
    }

    @Test fun changing_withoutADeviceLock_theChoiceSaysSo_andItCannotBeChosen() {
        deviceLock = false
        val vm = vm()
        vm.changeProtection()
        assertFalse((vm.protection.value as ProtectionStep.Choose).deviceLockAvailable)
        vm.chooseProtection(ProfileLockKind.DEVICE)
        assertNull(vm.gatePrompt.value)
        assertEquals(ProfileLockKind.NONE, runBlocking { locks.kind(fabian.serverId) })
    }

    @Test fun changing_aProtectedOne_asksFirst_withAPin() {
        locks.pin(fabian.serverId)
        val vm = vm()
        vm.changeProtection()
        assertTrue(vm.gatePrompt.value is GatePrompt.Pin)
        assertNull(vm.protection.value)
        vm.submitPin("9999")
        assertNull(vm.protection.value)
        vm.submitPin("1234")
        assertEquals(ProfileLockKind.PIN, (vm.protection.value as ProtectionStep.Choose).current)
    }

    @Test fun changing_aProtectedOne_asksFirst_withTheDeviceLock() {
        locks.device(fabian.serverId)
        val vm = vm()
        vm.changeProtection()
        assertTrue(vm.gatePrompt.value is GatePrompt.Device)
        vm.deviceLockAnswered(DeviceLockAnswer.CANCELLED)
        assertNull(vm.protection.value)
        assertEquals(ProfileLockKind.DEVICE, runBlocking { locks.kind(fabian.serverId) })
    }

    @Test fun turningItOff_removesIt() {
        locks.pin(fabian.serverId)
        val vm = vm()
        vm.changeProtection()
        vm.submitPin("1234")
        vm.chooseProtection(ProfileLockKind.NONE)
        assertNull(vm.protection.value)
        assertEquals(ProfileLockKind.NONE, runBlocking { locks.kind(fabian.serverId) })
        assertEquals(ProfileLockKind.NONE, vm.row("Fabian").protection)
    }

    @Test fun choosingADeviceLock_confirmsItOnce_thenItIsSet() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.DEVICE)
        assertTrue((vm.gatePrompt.value as GatePrompt.Device).confirmingNew)
        assertEquals("not before it was seen to work", ProfileLockKind.NONE, runBlocking { locks.kind(fabian.serverId) })
        vm.deviceLockAnswered(DeviceLockAnswer.PASSED)
        assertNull(vm.gatePrompt.value)
        assertNull(vm.protection.value)
        assertEquals(ProfileLockKind.DEVICE, runBlocking { locks.kind(fabian.serverId) })
    }

    @Test fun choosingADeviceLock_cancelled_backToTheChoice_nothingSet() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.DEVICE)
        vm.deviceLockAnswered(DeviceLockAnswer.CANCELLED)
        assertNull(vm.gatePrompt.value)
        assertTrue(vm.protection.value is ProtectionStep.Choose)
        assertEquals(ProfileLockKind.NONE, runBlocking { locks.kind(fabian.serverId) })
    }

    @Test fun choosingADeviceLock_thatIsGoneByNow_backToTheChoice_whichSaysSo() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.DEVICE)
        deviceLock = false
        vm.deviceLockAnswered(DeviceLockAnswer.NOT_AVAILABLE)
        assertNull(vm.gatePrompt.value)
        assertFalse((vm.protection.value as ProtectionStep.Choose).deviceLockAvailable)
    }

    @Test fun choosingAPin_setsIt_forTheAccountThatPassed() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.PIN)
        assertTrue(vm.protection.value is ProtectionStep.NewPin)
        vm.setNewPin("567890")
        assertNull(vm.protection.value)
        assertEquals("567890", locks.pins[fabian.serverId])
        assertEquals(setOf(fabian.serverId), locks.all.value.keys)
    }

    @Test fun aNewPin_notAPin_setsNothing() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.PIN)
        vm.setNewPin("12")
        assertTrue(vm.protection.value is ProtectionStep.NewPin)
        assertTrue(locks.pins.isEmpty())
    }

    @Test fun whileTheNewPinIsSaved_itSaysSo() {
        val hold = CompletableDeferred<Unit>()
        locks.hold = hold
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.PIN)
        vm.setNewPin("1234")
        assertTrue((vm.protection.value as ProtectionStep.NewPin).saving)
        hold.complete(Unit)
        assertNull(vm.protection.value)
    }

    @Test fun whatCannotBeSaved_backToTheChoice_whichSaysSo_nothingChanged() {
        locks.failWriting = true
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.PIN)
        vm.setNewPin("1234")
        assertTrue((vm.protection.value as ProtectionStep.Choose).saveFailed)
        assertTrue(locks.pins.isEmpty())
    }

    @Test fun theChoice_sameAsBefore_justCloses() {
        locks.pin(fabian.serverId)
        val vm = vm()
        vm.changeProtection()
        vm.submitPin("1234")
        vm.chooseProtection(ProfileLockKind.PIN)
        assertTrue("the same kind with a PIN: a new PIN", vm.protection.value is ProtectionStep.NewPin)
        vm.cancelProtection()
        assertNull(vm.protection.value)
        assertEquals("1234", locks.pins[fabian.serverId])
    }

    @Test fun theChoice_goesWhenTheActiveAccountChanges() {
        val vm = vm()
        vm.changeProtection()
        vm.chooseProtection(ProfileLockKind.PIN)
        runBlocking { store.activate(kind.serverId) }
        assertNull(vm.protection.value)
        vm.setNewPin("1234")
        assertTrue(locks.pins.isEmpty())
    }

    @Test fun nothingOfAPinInWhatTheScreenHolds() {
        locks.pin(kind.serverId)
        val vm = vm()
        vm.switchTo(vm.row("kind"))
        vm.submitPin("86420")
        assertFalse("86420" in vm.gatePrompt.value.toString())
    }
}
