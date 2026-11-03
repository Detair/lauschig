package io.wolftown.lauschig.account

import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
