package io.wolftown.lauschig.settings

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.AccountSignOut
import io.wolftown.lauschig.account.SignOutCheck
import io.wolftown.lauschig.account.SignOutResult
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log on failures
class AccountSectionViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    private val viewModels = TestViewModels()
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private val accounts = FakeAccountStore().apply {
        acc.value = Account("aaaaaaaaaaaaaaaa", "u1", "fabian", true, ServerAddresses("http://10.0.2.2:13378".toHttpUrl(), null), emptySet())
    }

    private class FakeSignOut(private val accounts: FakeAccountStore) : AccountSignOut {
        var answer = CompletableDeferred(SignOutCheck(unsynced = 2, downloadBytes = 3_000_000_000))
        var checks = 0
        val executed = mutableListOf<Boolean>()
        var gate: CompletableDeferred<Unit>? = null
        var changed: SignOutCheck? = null
        val shown = mutableListOf<Int?>()
        override suspend fun check(): SignOutCheck? { checks++; return answer.await() }
        override suspend fun execute(keepDownloads: Boolean, shownUnsynced: Int?): SignOutResult {
            executed += keepDownloads
            shown += shownUnsynced
            gate?.await()
            changed?.let { changed = null; return SignOutResult.Changed(it) }
            accounts.clear(keepDownloads)
            return SignOutResult.Done
        }
    }

    private val signOut = FakeSignOut(accounts)
    private fun vm() = viewModels.own(AccountSectionViewModel(accounts, signOut))

    @Test fun accountRow_showsUserHostAndAdmin() = runBlocking {
        val vm = vm()
        assertEquals(AccountInfo("fabian", "10.0.2.2:13378", isAdmin = true), vm.account.first { it != null })
    }

    @Test fun signOut_checksFirst_thenAsks_withKeepCheckedByDefault() {
        signOut.answer = CompletableDeferred()
        val vm = vm()
        vm.startSignOut()
        assertEquals(SignOutStep.Checking, vm.step.value) // "Fortschritt wird übertragen …"
        vm.startSignOut() // a second tap does not start a second check
        assertEquals(1, signOut.checks)
        signOut.answer.complete(SignOutCheck(unsynced = 2, downloadBytes = 3_000_000_000))
        val confirm = vm.step.value as SignOutStep.Confirm
        assertEquals(2, confirm.unsynced)
        assertEquals(3_000_000_000, confirm.downloadBytes)
        assertTrue(confirm.keepDownloads)
        assertTrue(confirm.hasDownloads)
    }

    @Test fun confirm_withTheCheckboxCleared_signsOutWithoutKeeping() {
        val vm = vm()
        vm.startSignOut()
        vm.setKeepDownloads(false)
        assertEquals(false, (vm.step.value as SignOutStep.Confirm).keepDownloads)
        vm.confirm()
        assertEquals(listOf(false), signOut.executed)
        assertNull(vm.step.value)
        assertNull(accounts.acc.value) // the app turns to the sign-in (MainViewModel.signedIn)
    }

    @Test fun moreChangesThanShown_asksAgain_withTheSameChoice() {
        val vm = vm()
        vm.startSignOut()
        vm.setKeepDownloads(false)
        signOut.changed = SignOutCheck(unsynced = 5, downloadBytes = 3_000_000_000)
        vm.confirm()
        assertEquals(listOf<Int?>(2), signOut.shown) // what the dialog showed
        assertEquals(SignOutStep.Confirm(unsynced = 5, downloadBytes = 3_000_000_000, keepDownloads = false), vm.step.value)
        assertEquals("fabian", accounts.acc.value?.username) // still signed in
        vm.confirm()
        assertEquals(listOf<Int?>(2, 5), signOut.shown)
        assertNull(vm.step.value)
        assertNull(accounts.acc.value)
    }

    @Test fun confirm_keeping_showsProgress_andCannotBeCancelledMeanwhile() {
        signOut.gate = CompletableDeferred()
        val vm = vm()
        vm.startSignOut()
        vm.confirm()
        assertEquals(SignOutStep.SigningOut, vm.step.value)
        vm.cancel()
        vm.confirm()
        assertEquals(SignOutStep.SigningOut, vm.step.value)
        signOut.gate!!.complete(Unit)
        assertEquals(listOf(true), signOut.executed)
        assertNull(vm.step.value)
    }

    @Test fun noDownloads_nothingToKeep() {
        signOut.answer = CompletableDeferred(SignOutCheck(unsynced = 0, downloadBytes = 0))
        val vm = vm()
        vm.startSignOut()
        val confirm = vm.step.value as SignOutStep.Confirm
        assertEquals(false, confirm.hasDownloads)
        assertEquals(false, confirm.canKeep)
        vm.confirm()
        assertEquals(listOf(false), signOut.executed)
    }

    @Test fun noDownloads_butUnsyncedChanges_keepsTheDataByDefault() {
        signOut.answer = CompletableDeferred(SignOutCheck(unsynced = 3, downloadBytes = 0))
        val vm = vm()
        vm.startSignOut()
        val confirm = vm.step.value as SignOutStep.Confirm
        assertTrue(confirm.canKeep)
        assertTrue(confirm.keepDownloads)
        vm.confirm()
        assertEquals(listOf(true), signOut.executed)
    }

    @Test fun cancel_whileChecking_orAsking_closesWithoutSigningOut() {
        signOut.answer = CompletableDeferred()
        val vm = vm()
        vm.startSignOut()
        vm.cancel()
        assertNull(vm.step.value)
        signOut.answer.complete(SignOutCheck(1, 1))
        assertNull(vm.step.value) // a late answer does not reopen it
        vm.startSignOut()
        vm.cancel()
        assertNull(vm.step.value)
        assertEquals(emptyList<Boolean>(), signOut.executed)
        assertEquals("fabian", accounts.acc.value?.username)
    }

    @Test fun aFailingCheck_stillAsks_withAnUnknownCount_keepingByDefault() {
        signOut.answer = CompletableDeferred<SignOutCheck>().apply { completeExceptionally(IllegalStateException("db")) }
        val vm = vm()
        vm.startSignOut()
        val confirm = vm.step.value as SignOutStep.Confirm
        assertEquals(SignOutStep.Confirm(unsynced = null, downloadBytes = 0), confirm)
        assertTrue(confirm.canKeep)
        assertEquals("fabian", accounts.acc.value?.username)
        vm.confirm()
        assertEquals(listOf(true), signOut.executed)
        assertEquals(listOf<Int?>(null), signOut.shown)
    }
}
