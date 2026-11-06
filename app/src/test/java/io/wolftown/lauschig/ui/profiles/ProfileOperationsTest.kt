package io.wolftown.lauschig.ui.profiles

import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.ProfileSwitch
import io.wolftown.lauschig.connection.ServerAddresses
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class) // Log on failures
@Config(sdk = [35], application = android.app.Application::class)
class ProfileOperationsTest {
    private class FakeProfiles : ProfileSwitch {
        var gate: CompletableDeferred<Unit>? = null
        var switchResult: () -> Boolean = { true }
        var removeResult: () -> Boolean = { true }
        var addResult: () -> Unit = {}
        val calls = mutableListOf<String>()
        override suspend fun switchProfile(serverId: String): Boolean { calls += "switch:$serverId"; gate?.await(); return switchResult() }
        override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) { calls += "add:${next.serverId}"; gate?.await(); addResult() }
        override suspend fun removeProfile(serverId: String): Boolean { calls += "remove:$serverId"; gate?.await(); return removeResult() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val profiles = FakeProfiles()
    private val ops = ProfileOperations(profiles, scope)
    private val kind = Account("kind", "u2", "kind", false, ServerAddresses(null, null), emptySet())

    @After fun tearDown() = scope.cancel()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test fun whileASwitchRuns_itIsShown_andAfterwardsTheResultNamesTheProfile() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        assertTrue(ops.switchTo("b", "Kind"))
        assertEquals(ProfileWork.Switching("Kind"), ops.running.value)
        assertNull(ops.outcome.value)

        gate.complete(Unit)
        val outcome = await { ops.outcome.filterNotNull().first() }
        assertEquals(ProfileOutcome.Kind.SWITCHED, outcome.kind)
        assertEquals("Kind", outcome.name)
        assertNull(ops.running.value)
    }

    @Test fun nothingStartsOnTopOfARunningOperation() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        assertTrue(ops.switchTo("b", "Kind"))
        assertFalse(ops.switchTo("c", "Oma"))
        assertFalse(ops.remove("c", "Oma"))
        assertEquals(listOf("switch:b"), profiles.calls)
        gate.complete(Unit)
        await { ops.outcome.filterNotNull().first() }
        // Once it is done the next one may start.
        profiles.gate = null
        assertTrue(ops.switchTo("c", "Oma"))
    }

    @Test fun aSwitchThatFails_saysSo_andIsDone() {
        profiles.switchResult = { false }
        ops.switchTo("b", "Kind")
        assertEquals(ProfileOutcome.Kind.SWITCH_FAILED, await { ops.outcome.filterNotNull().first() }.kind)
        assertNull(ops.running.value)
    }

    @Test fun aSwitchThatThrows_saysSo_andIsDone() {
        profiles.switchResult = { throw IOException("store gone") }
        ops.switchTo("b", "Kind")
        assertEquals(ProfileOutcome.Kind.SWITCH_FAILED, await { ops.outcome.filterNotNull().first() }.kind)
        assertNull(ops.running.value)
    }

    @Test fun theResultWaitsForAScreen_untilOneHasShownIt() {
        ops.switchTo("b", "Kind")
        val outcome = await { ops.outcome.filterNotNull().first() }
        // A screen recreated in the meantime still finds it.
        assertEquals(outcome, ops.outcome.value)
        ops.shown(outcome)
        assertNull(ops.outcome.value)
    }

    @Test fun anOlderResultBeingShown_doesNotTakeTheNewerOneAway() {
        ops.switchTo("b", "Kind")
        val first = await { ops.outcome.filterNotNull().first() }
        ops.switchTo("a", "Fabian")
        val second = await { ops.outcome.first { it != null && it != first } }!!
        ops.shown(first)
        assertEquals(second, ops.outcome.value)
    }

    @Test fun twoSwitchesToTheSameProfile_areTwoResults() {
        ops.switchTo("b", "Kind")
        val first = await { ops.outcome.filterNotNull().first() }
        ops.switchTo("b", "Kind")
        await { ops.outcome.first { it != null && it != first } }
    }

    @Test fun removing_isShown_andNamesTheProfile() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        assertTrue(ops.remove("b", "Kind"))
        assertEquals(ProfileWork.Removing("Kind"), ops.running.value)
        gate.complete(Unit)
        val outcome = await { ops.outcome.filterNotNull().first() }
        assertEquals(ProfileOutcome.Kind.REMOVED, outcome.kind)
        assertEquals("Kind", outcome.name)
    }

    @Test fun aRemovalThatDoesNotHappen_saysSo() {
        profiles.removeResult = { false }
        ops.remove("b", "Kind")
        assertEquals(ProfileOutcome.Kind.REMOVE_FAILED, await { ops.outcome.filterNotNull().first() }.kind)
    }

    @Test fun adding_isAShownSwitch_toTheNewProfile() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        val added = scope.launch { ops.add(kind, "acc", "ref") }
        assertEquals(ProfileWork.Switching("kind"), ops.running.value)
        gate.complete(Unit)
        await { added.join() }
        assertEquals(ProfileOutcome.Kind.SWITCHED, ops.outcome.value!!.kind)
        assertEquals("kind", ops.outcome.value!!.name)
    }

    @Test fun addingThatFails_reachesTheForm_andLeavesNoResult() {
        profiles.addResult = { throw IOException("disk full") }
        val thrown = await { runCatching { ops.add(kind, "acc", "ref") }.exceptionOrNull() }
        assertTrue(thrown is IOException)
        assertNull(ops.running.value)
        assertNull(ops.outcome.value)
    }

    /** The form goes with the old account's pages at the moment the account changes; the switch still has to arrive. */
    @Test fun theFormGoingAway_doesNotEndTheAdding() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        val form = scope.launch { ops.add(kind, "acc", "ref") }
        await { form.cancelAndJoin() }
        assertEquals(ProfileWork.Switching("kind"), ops.running.value)
        gate.complete(Unit)
        assertEquals(ProfileOutcome.Kind.SWITCHED, await { ops.outcome.filterNotNull().first() }.kind)
        assertNull(ops.running.value)
    }

    @Test fun addingWaitsForARunningOperation_insteadOfLosingTheSignIn() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        ops.switchTo("b", "Kind")
        val added = scope.launch { ops.add(kind, "acc", "ref") }
        assertEquals(listOf("switch:b"), profiles.calls)
        gate.complete(Unit)
        await { added.join() }
        assertEquals(listOf("switch:b", "add:kind"), profiles.calls)
    }

    // --- 3.6, a forgotten PIN ---

    private val order = mutableListOf<String>()
    private var removal: () -> Unit = {}
    private val unlocking by lazy {
        ProfileOperations(object : ProfileSwitch by profiles {
            override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) {
                order += "add"
                profiles.addProfile(next, accessToken, refreshToken)
            }
        }, scope, removeProtection = {
            order += "protection:$it"
            removal()
        })
    }

    @Test fun anUnlock_comesInFirst_thenTheProtectionGoes_andItSaysSo() {
        await { unlocking.unlock(kind, "a", "r") }
        assertEquals(listOf("add", "protection:kind"), order)
        assertEquals(ProfileOutcome.Kind.UNLOCKED, unlocking.outcome.value!!.kind)
        assertEquals("kind", unlocking.outcome.value!!.name)
        assertNull(unlocking.running.value)
    }

    @Test fun anUnlock_whileItRuns_isShown() {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        val job = scope.launch { unlocking.unlock(kind, "a", "r") }
        assertEquals(ProfileWork.Switching("kind"), unlocking.running.value)
        assertTrue("not before it is in", order == listOf("add"))
        gate.complete(Unit)
        await { job.join() }
    }

    /** It cannot be stored: the form still says so, and the protection stays. */
    @Test fun anUnlock_thatCannotComeIn_keepsTheProtection() {
        profiles.addResult = { throw IOException("disk full") }
        try {
            await { unlocking.unlock(kind, "a", "r") }
            org.junit.Assert.fail("the form has to hear of it")
        } catch (_: IOException) {
        }
        assertEquals(listOf("add"), order)
        assertNull(unlocking.outcome.value)
        assertNull(unlocking.running.value)
    }

    /** In, and the protection stayed on: nothing that says it is off. */
    @Test fun aProtectionThatDoesNotGo_saysNothingOfThat() {
        removal = { throw IOException("disk full") }
        await { unlocking.unlock(kind, "a", "r") }
        assertEquals(ProfileOutcome.Kind.SWITCHED, unlocking.outcome.value!!.kind)
    }
}
