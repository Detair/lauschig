package io.wolftown.lauschig.ui

import io.wolftown.lauschig.TestViewModels
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.account.FakeKeptAccounts
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class) // Log
class PreviousAccountViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    private val viewModels = TestViewModels()
    @After fun tearDown() { viewModels.clear(); Dispatchers.resetMain() }

    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val c = "cccccccccccccccc"
    private fun account(sid: String, name: String) = Account(sid, "u-$name", name, false, ServerAddresses(null, null), emptySet())

    private val accounts = FakeAccountStore()
    private val kept = FakeKeptAccounts(listOf(KeptAccount(a, "fabian", "abs.example.org")))
    private val purged = mutableListOf<String>()
    private var purgeGate: CompletableDeferred<Unit>? = null
    private var purgeFails = false
    private val data = object : io.wolftown.lauschig.account.AccountData {
        override suspend fun unsynced(serverId: String) = 0
        override suspend fun downloadBytes(serverId: String) = if (serverId == a) 2_000_000_000L else 5L
        override suspend fun pauseDownloads(serverId: String) {}
        override suspend fun purge(serverId: String) { purgeGate?.await(); if (purgeFails) throw java.io.IOException("disk full"); purged += serverId }
        override suspend fun deleteRows(serverId: String) {}
        override suspend fun hasRows(serverId: String) = false
    }

    private fun vm() = viewModels.own(
        PreviousAccountViewModel(accounts, kept, data::downloadBytes, io.wolftown.lauschig.account.KeptAccountDeletion(data, kept)::delete),
    )

    private fun signIn(sid: String, name: String) = runBlocking { accounts.save(account(sid, name), "x", "y") }

    @Test fun signedOut_orTheSameAccount_noQuestion_andItsKeptEntryGoes() {
        val vm = vm()
        assertNull(vm.question.value) // nobody signed in yet (the Setup screen)
        signIn(a, "fabian")
        assertNull(vm.question.value)
        assertEquals(emptyList<KeptAccount>(), kept.list.value) // its data is simply its data again
    }

    @Test fun theSameAccount_signingOutAndInAgain_whileTheAppRuns_getsItsDataBack() {
        runBlocking { kept.remove(a) }
        signIn(a, "fabian")
        val vm = vm()
        // Sign-out with "keep", then the same account signs in again in the same process.
        runBlocking { kept.put(KeptAccount(a, "fabian", "abs.example.org")); accounts.clear(true) }
        signIn(a, "fabian")
        assertNull(vm.question.value)
        assertEquals(emptyList<KeptAccount>(), kept.list.value)
    }

    @Test fun aSignIn_clearsAPendingPurgeOfThatAccount() {
        runBlocking { kept.addPendingPurge(b); kept.addPendingPurge(c) }
        vm()
        signIn(b, "matthias")
        assertEquals(setOf(c), kept.pending.value) // its data is live again; another account's stays pending
    }

    @Test fun anotherAccount_isAskedOnce_withTheSize() {
        val vm = vm()
        signIn(b, "matthias")
        assertEquals(PreviousAccountQuestion(KeptAccount(a, "fabian", "abs.example.org"), 2_000_000_000), vm.question.value)
    }

    @Test fun keep_neverAsksAgain_evenForTheNextAccount() {
        val vm = vm()
        signIn(b, "matthias")
        vm.keep()
        assertNull(vm.question.value)
        assertEquals(listOf(KeptAccount(a, "fabian", "abs.example.org", asked = true)), kept.list.value)
        runBlocking { accounts.clear(true) }
        signIn(c, "lukas")
        assertNull(vm.question.value)
        assertEquals(emptyList<String>(), purged)
    }

    @Test fun delete_purgesOnlyThatAccount_andForgetsIt() {
        purgeGate = CompletableDeferred()
        val vm = vm()
        signIn(b, "matthias")
        vm.delete()
        assertTrue(vm.deleting.value)
        purgeGate!!.complete(Unit)
        assertEquals(listOf(a), purged)
        assertEquals(emptyList<KeptAccount>(), kept.list.value)
        assertNull(vm.question.value)
        assertEquals(false, vm.deleting.value)
    }

    @Test fun aFailingDelete_neverCrashes_keepsTheQuestionWithAMessage_andCanBeRetried() {
        purgeFails = true
        val vm = vm()
        signIn(b, "matthias")
        vm.delete()
        assertTrue(vm.deleteFailed.value)
        assertEquals(false, vm.deleting.value)
        assertEquals(listOf(KeptAccount(a, "fabian", "abs.example.org")), kept.list.value) // still reachable
        assertEquals(a, vm.question.value!!.account.serverId)
        purgeFails = false
        vm.delete()
        assertEquals(false, vm.deleteFailed.value)
        assertEquals(listOf(a), purged)
        assertNull(vm.question.value)
    }

    @Test fun severalKeptAccounts_oneQuestionAfterTheOther() {
        runBlocking { kept.put(KeptAccount(c, "lukas", "nas")) }
        val vm = vm()
        signIn(b, "matthias")
        assertEquals(a, vm.question.value!!.account.serverId)
        vm.keep()
        assertEquals(PreviousAccountQuestion(KeptAccount(c, "lukas", "nas"), 5), vm.question.value)
        vm.delete()
        assertEquals(listOf(c), purged)
        assertNull(vm.question.value)
    }
}
