package io.wolftown.lauschig.ui.profiles

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.ProfileSwitch
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log on failures
@Config(sdk = [35], application = android.app.Application::class)
class ProfilesViewModelTest {
    private val family = ServerAddresses(null, "https://abs.example.org".toHttpUrl())
    private val fabian = Account("aaaaaaaaaaaaaaaa", "u1", "Fabian", true, family, emptySet())
    private val kind = Account("bbbbbbbbbbbbbbbb", "u2", "kind", false, family, emptySet())
    private val oma = Account("cccccccccccccccc", "u3", "Oma", false, ServerAddresses("http://nas.lan:13378".toHttpUrl(), null), emptySet())

    private val store = FakeAccountStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private inner class FakeProfiles : ProfileSwitch {
        var gate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()
        override suspend fun switchProfile(serverId: String): Boolean { calls += "switch:$serverId"; gate?.await(); return store.activate(serverId) }
        override suspend fun addProfile(next: Account, accessToken: String, refreshToken: String) = store.add(next, accessToken, refreshToken)
        override suspend fun removeProfile(serverId: String): Boolean { calls += "remove:$serverId"; gate?.await(); return store.forget(serverId) }
    }

    private val profiles = FakeProfiles()
    private val ops = ProfileOperations(profiles, scope)
    private val viewModels = TestViewModels()
    private var facts: suspend (String) -> RemovalFacts = { RemovalFacts(2_100_000_000, 0, false) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() { viewModels.clear(); scope.cancel(); Dispatchers.resetMain() }

    private fun vm() = viewModels.own(ProfilesViewModel(store, ops) { facts(it) })
    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    private fun aside(vararg accounts: Account, flagged: Set<String> = emptySet()) {
        store.aside.value = accounts.map { FakeAccountStore.Aside(it, "acc", "ref", it.serverId in flagged) }
    }

    @Test fun oneAccount_oneRow_activeWithoutAHost() {
        store.acc.value = fabian
        val rows = vm().profiles.value!!
        assertEquals(listOf(ProfileRow(fabian.serverId, "Fabian", null, active = true, needsReauth = false)), rows)
    }

    @Test fun theServerIsOnlyNamedWhenTheServersDiffer() {
        store.acc.value = fabian
        aside(kind)
        assertEquals(listOf(null, null), vm().profiles.value!!.map { it.host })
        aside(kind, oma)
        assertEquals(listOf("abs.example.org", "abs.example.org", "nas.lan:13378"), vm().profiles.value!!.map { it.host })
    }

    @Test fun theOrderDoesNotChangeWithTheActiveProfile() {
        store.acc.value = fabian
        aside(oma, kind)
        val vm = vm()
        assertEquals(listOf("Fabian", "kind", "Oma"), vm.profiles.value!!.map { it.name })
        runBlocking { store.activate(oma.serverId) }
        assertEquals(listOf("Fabian", "kind", "Oma"), vm.profiles.value!!.map { it.name })
        assertEquals(listOf(false, false, true), vm.profiles.value!!.map { it.active })
    }

    @Test fun theSignInHint_isPerProfile() {
        store.acc.value = fabian
        store.reauth.value = true
        aside(kind, oma, flagged = setOf(oma.serverId))
        assertEquals(listOf(true, false, true), vm().profiles.value!!.map { it.needsReauth })
    }

    /** Review focus 5: the flag travels with its account, so the return asks for exactly that one. */
    @Test fun anExpiredSavedAccount_stillHasItsHintWhenItIsActive_theOtherNot() {
        store.acc.value = fabian
        aside(kind, flagged = setOf(kind.serverId))
        val vm = vm()
        runBlocking { store.activate(kind.serverId) }
        val rows = vm.profiles.value!!
        assertTrue(rows.single { it.name == "kind" }.let { it.active && it.needsReauth })
        assertFalse(rows.single { it.name == "Fabian" }.needsReauth)
    }

    @Test fun signedOut_theSavedProfiles_noneActive() {
        aside(fabian, kind)
        assertEquals(listOf(false, false), vm().profiles.value!!.map { it.active })
    }

    @Test fun theHeaderAvatar_onlyFromTwoProfiles_isTheActiveOne() {
        store.acc.value = fabian
        val vm = vm()
        assertNull(headerProfile(vm.profiles.value))
        aside(kind)
        assertEquals("Fabian", headerProfile(vm.profiles.value)!!.name)
    }

    @Test fun tappingAProfile_switches_andWhatIsRunningIsThere() {
        store.acc.value = fabian
        aside(kind)
        profiles.gate = CompletableDeferred()
        val vm = vm()
        vm.switchTo(vm.profiles.value!!.single { it.name == "kind" })
        assertEquals(ProfileWork.Switching("kind"), vm.busy.value)
        profiles.gate!!.complete(Unit)
        assertEquals("kind", await { vm.outcome.filterNotNull().first() }.name)
        assertNull(vm.busy.value)
        assertEquals(kind, store.acc.value)
    }

    @Test fun tappingTheActiveProfile_doesNothing() {
        store.acc.value = fabian
        val vm = vm()
        vm.switchTo(vm.profiles.value!!.single())
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun aSecondTapWhileItRuns_startsNothing() {
        store.acc.value = fabian
        aside(kind, oma)
        profiles.gate = CompletableDeferred()
        val vm = vm()
        val rows = vm.profiles.value!!
        vm.switchTo(rows.single { it.name == "kind" })
        vm.switchTo(rows.single { it.name == "Oma" })
        vm.askRemove(rows.single { it.name == "Oma" })
        assertEquals(listOf("switch:${kind.serverId}"), profiles.calls)
        assertNull("no removal question on top", vm.removal.value)
        profiles.gate!!.complete(Unit)
    }

    /** A rotation in the middle of a switch; or the activity is gone and comes back. */
    @Test fun aScreenCreatedDuringASwitch_seesIt_andGetsTheResult() {
        store.acc.value = fabian
        aside(kind)
        profiles.gate = CompletableDeferred()
        val first = vm()
        first.switchTo(first.profiles.value!!.single { it.name == "kind" })
        val second = vm()
        assertEquals(ProfileWork.Switching("kind"), second.busy.value)
        profiles.gate!!.complete(Unit)
        val outcome = await { second.outcome.filterNotNull().first() }
        assertEquals(ProfileOutcome.Kind.SWITCHED, outcome.kind)
        second.shown(outcome)
        assertNull(first.outcome.value)
    }

    @Test fun removing_asksFirst_withTheSizeOfItsDownloads() {
        store.acc.value = fabian
        aside(kind)
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single { it.name == "kind" })
        val question = vm.removal.value!!
        assertEquals("kind", question.row.name)
        assertEquals(RemovalFacts(2_100_000_000, 0, false), question.facts)
        assertTrue(profiles.calls.isEmpty())
    }

    @Test fun removing_whileTheSizeIsRead_theQuestionIsThere_withoutFacts() {
        store.acc.value = fabian
        aside(kind)
        val read = CompletableDeferred<RemovalFacts>()
        facts = { read.await() }
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single { it.name == "kind" })
        assertNull(vm.removal.value!!.facts)
        vm.confirmRemove()
        assertTrue("not before it is known what goes", profiles.calls.isEmpty())
        read.complete(RemovalFacts(1, 2, true))
        assertEquals(RemovalFacts(1, 2, true), vm.removal.value!!.facts)
    }

    @Test fun removing_factsThatCannotBeRead_stillAsks_saysNoSize() {
        store.acc.value = fabian
        aside(kind)
        facts = { throw IOException("gone") }
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single { it.name == "kind" })
        assertEquals(RemovalFacts(0, null, false), vm.removal.value!!.facts)
    }

    @Test fun removing_cancelled_changesNothing() {
        store.acc.value = fabian
        aside(kind)
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single { it.name == "kind" })
        vm.cancelRemove()
        assertNull(vm.removal.value)
        assertTrue(profiles.calls.isEmpty())
        assertEquals(1, store.aside.value.size)
    }

    @Test fun removing_confirmed_goes_andSaysSo() {
        store.acc.value = fabian
        aside(kind)
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single { it.name == "kind" })
        vm.confirmRemove()
        assertNull(vm.removal.value)
        val outcome = await { vm.outcome.filterNotNull().first() }
        assertEquals(ProfileOutcome.Kind.REMOVED, outcome.kind)
        assertEquals(listOf("remove:${kind.serverId}"), profiles.calls)
        assertEquals(listOf("Fabian"), vm.profiles.value!!.map { it.name })
    }

    @Test fun theActiveProfile_cannotBeRemovedHere() {
        store.acc.value = fabian
        val vm = vm()
        vm.askRemove(vm.profiles.value!!.single())
        assertNull(vm.removal.value)
    }
}
