package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** At app start: failed purges are finished, download directories nobody can reach go, nothing else. */
@RunWith(RobolectricTestRunner::class) // Log
class AccountDataJanitorTest {
    private val current = "aaaaaaaaaaaaaaaa"
    private val failed = "bbbbbbbbbbbbbbbb"
    private val keptId = "cccccccccccccccc"
    private val orphan = "dddddddddddddddd"
    private val withRows = "eeeeeeeeeeeeeeee"

    private val accounts = FakeAccountStore().apply { acc.value = Account(current, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private val kept = FakeKeptAccounts(listOf(KeptAccount(keptId, "lukas", "h")))
    private val purged = mutableListOf<String>()
    private val deletedDirs = mutableListOf<String>()
    private var purgeFails = false
    private val dirs = mutableListOf(current, failed, keptId, orphan, withRows)

    private val data = object : AccountData {
        override suspend fun unsynced(serverId: String) = 0
        override suspend fun downloadBytes(serverId: String) = 0L
        override suspend fun pauseDownloads(serverId: String) {}
        override suspend fun purge(serverId: String) { if (purgeFails) throw java.io.IOException("disk"); purged += serverId; dirs -= serverId }
        override suspend fun deleteRows(serverId: String) {}
        override suspend fun hasRows(serverId: String) = serverId == withRows || serverId == current || serverId == keptId
    }

    private fun janitor() = AccountDataJanitor(accounts, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true })

    @Test fun finishesAFailedPurge_andDeletesOnlyUnreachableDirectories() = runTest {
        kept.pending.value = setOf(failed)
        janitor().run()
        assertEquals(listOf(failed), purged)
        assertEquals(emptySet<String>(), kept.pending.value)
        // The current account, a kept one and one with rows stay; only the directory nobody owns goes.
        assertEquals(listOf(orphan), deletedDirs)
    }

    @Test fun aPurgeThatFailsAgain_staysPending_andItsDirectoryStays() = runTest {
        kept.pending.value = setOf(failed)
        purgeFails = true
        janitor().run()
        assertEquals(setOf(failed), kept.pending.value)
        assertEquals(listOf(orphan), deletedDirs)
    }

    @Test fun aPendingPurgeOfAKeptAccount_isNeverRun_theKeepWins() = runTest {
        kept.pending.value = setOf(keptId, failed)
        janitor().run()
        assertEquals(listOf(failed), purged)
        assertEquals(emptySet<String>(), kept.pending.value)
        assertEquals(listOf(keptId), kept.list.value.map { it.serverId })
    }

    @Test fun anUnreadableAccountStore_isNeverTakenForSignedOut_nothingIsPurgedOrDeleted() = runTest {
        kept.pending.value = setOf(failed)
        val broken = object : AccountStore by accounts {
            override val account: kotlinx.coroutines.flow.Flow<Account?> = kotlinx.coroutines.flow.flow { throw java.io.IOException("corrupt") }
        }
        AccountDataJanitor(broken, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true }).run()
        assertEquals(emptyList<String>(), purged)
        assertEquals(emptyList<String>(), deletedDirs)
        assertEquals(setOf(failed), kept.pending.value)
    }

    @Test fun aSignInDuringTheRun_isSeenBeforeEachPurge() = runTest {
        kept.pending.value = setOf(failed)
        var reads = 0
        // Signed out at the start of the run; the pending account signs in right after.
        val changing = object : AccountStore by accounts {
            override val account: kotlinx.coroutines.flow.Flow<Account?> = kotlinx.coroutines.flow.flow {
                reads++
                emit(if (reads == 1) null else Account(failed, "u", "n", false, ServerAddresses(null, null), emptySet()))
            }
        }
        AccountDataJanitor(changing, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true }).run()
        assertEquals(emptyList<String>(), purged)
        assertEquals(emptySet<String>(), kept.pending.value) // its data is live again
        assertTrue(failed !in deletedDirs)
    }

    @Test fun theSameAccountSignedInAgain_isNotPurged() = runTest {
        kept.pending.value = setOf(current)
        janitor().run()
        assertEquals(emptyList<String>(), purged)
        assertEquals(emptySet<String>(), kept.pending.value)
    }

    @Test fun signedOut_keptDataStays_orphansGo() = runTest {
        accounts.acc.value = null
        janitor().run()
        // "current" has rows (a sign-out killed before its kept entry was written): it stays. "failed" is not pending
        // here and has no rows: nobody owns it.
        assertEquals(listOf(failed, orphan), deletedDirs)
    }

    @Test fun theSignedInAndKeptAccounts_areTidied_neverAPendingPurgeOrAnOrphan() = runTest {
        kept.pending.value = setOf(failed)
        purgeFails = true // stays pending
        val tidied = mutableListOf<String>()
        AccountDataJanitor(accounts, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true }, { sid -> tidied += sid; 1 }).run()
        assertEquals(listOf(current, keptId), tidied)
    }

    @Test fun aFailingTidy_neitherStopsTheOthers_norTheRun() = runTest {
        val tidied = mutableListOf<String>()
        AccountDataJanitor(accounts, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true }, { sid ->
            if (sid == current) throw java.io.IOException("card pulled")
            tidied += sid; 0
        }).run()
        assertEquals(listOf(keptId), tidied)
        assertEquals(listOf(failed, orphan), deletedDirs) // nothing pending here: "failed" has no owner either
    }

    @Test fun signedOut_onlyTheKeptAccountsAreTidied() = runTest {
        accounts.acc.value = null
        val tidied = mutableListOf<String>()
        AccountDataJanitor(accounts, kept, data, { dirs.toList() }, { true }, { sid -> tidied += sid; 0 }).run()
        assertEquals(listOf(keptId), tidied)
    }

    @Test fun aSavedAccount_ownsItsDirectory_andItsPendingPurgeIsVoid() = runTest {
        val saved = "ffffffffffffffff"
        dirs += saved // no rows yet (e.g. only a partial download)
        accounts.aside.value = listOf(io.wolftown.lauschig.net.FakeAccountStore.Aside(Account(saved, "u9", "lukas", false, ServerAddresses(null, null), emptySet()), "x", "y", false))
        kept.pending.value = setOf(saved)
        janitor().run()
        assertEquals(emptyList<String>(), purged)
        assertEquals(emptySet<String>(), kept.pending.value)
        // Only the directories nobody owns go.
        assertEquals(listOf(failed, orphan), deletedDirs)
    }

    // --- 3.7: a process that died between the account and its protection ---

    private val protections = mutableSetOf<String>()
    private fun janitorWithLocks(store: AccountStore = accounts) = AccountDataJanitor(
        store, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true },
        protectedIds = { protections.toSet() }, removeProtection = { protections -= it },
    )

    @Test fun aProtectionWithoutAnAccount_goes_theOthersStay() = runTest {
        accounts.aside.value = listOf(FakeAccountStore.Aside(Account(withRows, "u", "n", false, ServerAddresses(null, null), emptySet()), "a", "r", false))
        protections += setOf(current, withRows, orphan)
        janitorWithLocks().run()
        assertEquals(setOf(current, withRows), protections)
    }

    @Test fun anUnreadableAccountStore_touchesNoProtection() = runTest {
        protections += orphan
        val broken = object : AccountStore by accounts {
            override val saved: kotlinx.coroutines.flow.Flow<List<Account>> = kotlinx.coroutines.flow.flow { throw java.io.IOException("corrupt") }
        }
        janitorWithLocks(broken).run()
        assertEquals(setOf(orphan), protections)
    }

    @Test fun protectionsThatCannotBeRead_stopNothingElse() = runTest {
        AccountDataJanitor(
            accounts, kept, data, { dirs.toList() }, { sid -> deletedDirs += sid; true },
            protectedIds = { throw java.io.IOException("disk") },
        ).run()
        assertTrue(orphan in deletedDirs)
    }
}
