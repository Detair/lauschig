package io.wolftown.lauschig.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class) // Log
class KeptAccountDeletionTest {
    private val entry = KeptAccount("aaaaaaaaaaaaaaaa", "fabian", "abs.example.org")
    private val kept = FakeKeptAccounts(listOf(entry, KeptAccount("bbbbbbbbbbbbbbbb", "lukas", "nas")))
    private val purged = mutableListOf<String>()
    private var fails = false
    private val accounts = io.wolftown.lauschig.net.FakeAccountStore().apply {
        acc.value = Account("cccccccccccccccc", "u3", "matthias", false, io.wolftown.lauschig.connection.ServerAddresses(null, null), emptySet())
    }
    private val data = object : AccountData {
        override suspend fun unsynced(serverId: String) = 0
        override suspend fun downloadBytes(serverId: String) = 0L
        override suspend fun pauseDownloads(serverId: String) {}
        override suspend fun purge(serverId: String) { if (fails) throw java.io.IOException("disk full"); purged += serverId }
        override suspend fun deleteRows(serverId: String) {}
        override suspend fun hasRows(serverId: String) = false
    }

    @Test fun success_purgesThatAccountOnly_andForgetsIt() = runTest {
        assertTrue(KeptAccountDeletion(accounts, data, kept).delete(entry))
        assertEquals(listOf(entry.serverId), purged)
        assertEquals(listOf("bbbbbbbbbbbbbbbb"), kept.list.value.map { it.serverId })
    }

    @Test fun failure_keepsTheEntry_soTheDataStaysReachable() = runTest {
        fails = true
        // As if an earlier version had already dropped it: it comes back.
        kept.remove(entry.serverId)
        assertFalse(KeptAccountDeletion(accounts, data, kept).delete(entry))
        assertTrue(entry in kept.list.value)
    }

    @Test fun theSignedInAccount_isNeverDeleted() = runTest {
        val signedIn = KeptAccount("cccccccccccccccc", "matthias", "h")
        kept.put(signedIn) // as if an entry were stale
        assertFalse(KeptAccountDeletion(accounts, data, kept).delete(signedIn))
        assertEquals(emptyList<String>(), purged)
        assertTrue(signedIn in kept.list.value)
    }

    @Test fun anUnreadableAccountStore_deletesNothing() = runTest {
        val broken = object : AccountStore by accounts {
            override val account: kotlinx.coroutines.flow.Flow<Account?> = kotlinx.coroutines.flow.flow { throw java.io.IOException("corrupt") }
        }
        assertFalse(KeptAccountDeletion(broken, data, kept).delete(entry))
        assertEquals(emptyList<String>(), purged)
    }
}
