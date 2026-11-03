package io.wolftown.lauschig.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.playback.LastPlayedStore
import io.wolftown.lauschig.sync.InMemorySyncStamps
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SignOutTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val dev = DeviceLibrary.SERVER_ID
    private val localKey = "${DeviceLibrary.ID_PREFIX}0123456789abcdef01234567"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var root: File
    private lateinit var data: AccountData
    private val stamps = InMemorySyncStamps()
    private val forgottenCovers = mutableListOf<String>()
    private val account = Account(a, "u1", "fabian", false, ServerAddresses("http://10.0.2.2:13378".toHttpUrl(), "https://abs.example.org".toHttpUrl()), emptySet())
    private val accounts = FakeAccountStore()
    private val kept = FakeKeptAccounts()
    private val calls = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val logouts = mutableListOf<String>()
    private val lastPlayed = object : LastPlayedStore {
        var value: LastPlayed? = null
        override suspend fun get() = value
        override suspend fun set(last: LastPlayed) { value = last }
        override suspend fun clear() { value = null; calls += "lastPlayed" }
    }

    @Before fun setUp() {
        // Synchronous: the timeouts run on virtual time, a real query thread would let them fire early.
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        root = File(ctx.cacheDir, "signout-${System.nanoTime()}/downloads").apply { mkdirs() }
        data = DeviceAccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), DownloadFiles(root, db.audioFileDao()), stamps, forgottenCovers::add)
        accounts.acc.value = account
        accounts.onClear = { calls += "clear" }
        accounts.access = "access-1"
        accounts.refresh = "refresh-1"
    }

    @After fun tearDown() {
        db.close()
        root.parentFile!!.deleteRecursively()
    }

    private fun signOut(
        logout: suspend (Account, String) -> Boolean = { _, token -> logouts += token; calls += "logout"; true },
        online: suspend () -> Boolean = { true },
        pushes: List<suspend (String) -> Unit> = emptyList(),
        purgeData: AccountData = data,
    ) = SignOut(
        accounts = accounts,
        data = Recording(purgeData),
        kept = recordingKept,
        work = { sid -> cancelled += sid; calls += "work" },
        lastPlayed = lastPlayed,
        pausePlayback = { calls += "pause" },
        stopPlayback = { calls += "stop" },
        pushes = pushes,
        awaitOnline = online,
        logout = logout,
        clearAccountState = { sid -> calls += "state:$sid" },
    )

    /** Records the data steps of a sign-out in [calls]. */
    private inner class Recording(private val inner: AccountData) : AccountData by inner {
        override suspend fun purge(serverId: String) { calls += "purge"; inner.purge(serverId) }
        override suspend fun pauseDownloads(serverId: String) { calls += "pauseDownloads"; inner.pauseDownloads(serverId) }
        override suspend fun deleteRows(serverId: String) { calls += "rowsAgain"; inner.deleteRows(serverId) }
    }

    private val recordingKept = object : KeptAccountStore by kept {
        override suspend fun put(account: KeptAccount) { calls += "remember"; kept.put(account) }
        override suspend fun remove(serverId: String) { calls += "forget"; kept.remove(serverId) }
    }

    /** One title of [server] with progress, a complete download with its file, a bookmark, a queue entry and audio settings. */
    private suspend fun seed(server: String, id: String = "book1") {
        db.itemDao().upsertFromList(listOf(ItemEntity(server, id, "lib", "Title $id")))
        db.progressDao().saveLocal(server, id, 10.0, 100.0, false, 1)
        db.downloadDao().upsert(DownloadEntity(server, id, DownloadState.COMPLETE, 1))
        db.bookmarkDao().add(server, id, 5.0, "n", 1)
        db.queueDao().add(server, id, 1)
        db.titleAudioDao().setSpeed(server, id, 1.5f)
        if (server != dev) File(root, "$server/$id").apply { mkdirs() }.resolve("0.mp3").writeBytes(ByteArray(100))
    }

    private suspend fun rows(server: String, id: String = "book1") = listOf(
        db.itemDao().get(server, id) != null,
        db.progressDao().get(server, id) != null,
        db.downloadDao().get(server, id) != null,
        db.bookmarkDao().all(server).isNotEmpty(),
        db.queueDao().entries(server).isNotEmpty(),
        db.titleAudioDao().get(server, id) != null,
    )

    @Test fun withoutKeeping_deletesOnlyThisAccount_andSignsOut() = runTest {
        seed(a); seed(b); seed(dev, localKey)
        lastPlayed.value = LastPlayed(a, "book1", 1)

        signOut().execute(keepDownloads = false)

        assertEquals(List(6) { false }, rows(a))
        assertFalse(File(root, a).exists())
        assertEquals(List(6) { true }, rows(b))
        assertTrue(File(root, "$b/book1/0.mp3").isFile)
        assertEquals(List(6) { true }, rows(dev, localKey))
        assertNull(accounts.acc.value)
        assertNull(accounts.access)
        assertNull(accounts.refresh)
        assertEquals(false, accounts.clearedKeeping)
        assertEquals(listOf(a), cancelled)
        assertEquals(listOf("refresh-1"), logouts)
        assertNull(lastPlayed.value)
        assertEquals(emptyList<KeptAccount>(), kept.list.value)
        assertEquals(emptySet<String>(), kept.pending.value)
        // Playback first, then the work (no sync rewrites rows after the purge), the data, and the credentials last
        // (nothing reacts to "signed out" before the data is final). The logout runs alongside, awaited before the end.
        assertEquals(listOf("stop", "work", "purge", "forget", "lastPlayed", "state:$a", "rowsAgain", "clear"), calls - "logout")
        assertTrue(calls.indexOf("logout") in calls.indexOf("work") until calls.indexOf("rowsAgain"))
    }

    @Test fun keeping_keepsRowsAndFiles_pausesWaitingDownloads_andRemembersTheAccount() = runTest {
        seed(a)
        stamps.set("progress_sync:$a", 7)
        db.downloadDao().upsert(DownloadEntity(a, "book2", DownloadState.QUEUED, 1))
        lastPlayed.value = LastPlayed(a, "book1", 1)

        signOut().execute(keepDownloads = true)

        assertEquals(List(6) { true }, rows(a))
        assertTrue(File(root, "$a/book1/0.mp3").isFile)
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(a, "book2")!!.state)
        assertEquals(DownloadState.COMPLETE, db.downloadDao().get(a, "book1")!!.state)
        assertEquals(listOf(KeptAccount(a, "fabian", "abs.example.org", userId = "u1")), kept.list.value)
        assertNull(accounts.acc.value)
        assertNull(accounts.refresh)
        assertEquals(true, accounts.clearedKeeping)
        assertEquals(listOf("stop", "work", "pauseDownloads", "remember", "lastPlayed", "state:$a", "clear"), calls - "logout")
        // Kept means kept: the sync stamps and the car's covers of the account stay too.
        assertEquals(7L, stamps.get("progress_sync:$a"))
        assertEquals(emptyList<String>(), forgottenCovers)
        assertEquals(listOf(a), cancelled)
        assertEquals(listOf("refresh-1"), logouts)
        assertNull(lastPlayed.value) // a play key cannot start the old account's title
    }

    @Test fun moreChangesAfterStopping_thanShown_abortWithoutDeleting() = runTest {
        seed(a) // 2 unsynced: the dirty progress row and the pending bookmark
        val out = SignOut(
            accounts, Recording(data), recordingKept, { calls += "work" }, lastPlayed,
            pausePlayback = {},
            // Playback was resumed while the dialog was open: stopping it saves one more listening session.
            stopPlayback = { calls += "stop"; db.sessionDao().upsert(io.wolftown.lauschig.db.entities.LocalSessionEntity("s", a, "book1", 0.0, 9.0, 9.0, 1, 2)) },
            pushes = emptyList(), awaitOnline = { true }, logout = { _, _ -> calls += "logout"; true }, clearAccountState = {},
        )
        val result = out.execute(keepDownloads = false, shownUnsynced = 2)
        assertEquals(SignOutResult.Changed(SignOutCheck(3, 100)), result)
        assertEquals(listOf("stop"), calls) // nothing cancelled, deleted or cleared
        assertEquals(List(6) { true }, rows(a))
        assertEquals(account, accounts.acc.value)
        // Asked again with 3: it goes through.
        assertEquals(SignOutResult.Done, out.execute(keepDownloads = false, shownUnsynced = 3))
        assertNull(accounts.acc.value)
        assertEquals(List(6) { false }, rows(a))
    }

    @Test fun keepingOrAnUnknownCount_neverAborts() = runTest {
        seed(a)
        assertEquals(SignOutResult.Done, signOut().execute(keepDownloads = true, shownUnsynced = 0))
        accounts.acc.value = account; accounts.refresh = "r"
        assertEquals(SignOutResult.Done, signOut().execute(keepDownloads = false, shownUnsynced = null))
        assertNull(accounts.acc.value)
    }

    @Test fun aFailedPurge_thenTheSameAccountKeepsItsData_theNextStartKeepsEverything() = runTest {
        // 1. Sign-out without keeping, the purge fails: a pending purge is recorded.
        seed(a)
        val failing = DeviceAccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), DownloadFiles(root, db.audioFileDao()), stamps, { error("disk") })
        signOut(purgeData = failing).execute(keepDownloads = false)
        assertEquals(setOf(a), kept.pending.value)
        // 2. The same account signs in again (same process) and downloads again.
        accounts.save(account, "access-2", "refresh-2")
        seed(a)
        // 3. It signs out with the default "Downloads behalten".
        signOut().execute(keepDownloads = true)
        assertEquals(emptySet<String>(), kept.pending.value)
        // 4. The next start (signed out) keeps everything the user chose to keep.
        AccountDataJanitor(accounts, kept, data, { DownloadFiles(root, db.audioFileDao()).serverIds() }, { DownloadFiles(root, db.audioFileDao()).deleteServer(it) }).run()
        assertEquals(List(6) { true }, rows(a))
        assertTrue(File(root, "$a/book1/0.mp3").isFile)
        assertEquals(listOf(a), kept.list.value.map { it.serverId })
    }

    @Test fun aLateWriteAfterThePurge_isDeletedToo() = runTest {
        seed(a)
        // The stopping service's last progress save lands after the purge (it runs on the service's own scope).
        val out = SignOut(
            accounts, Recording(data), recordingKept, { calls += "work" }, lastPlayed,
            pausePlayback = {}, stopPlayback = {}, pushes = emptyList(), awaitOnline = { true },
            logout = { _, _ -> db.progressDao().saveLocal(a, "book1", 20.0, 100.0, false, 2); true },
            clearAccountState = { db.sessionDao().upsert(io.wolftown.lauschig.db.entities.LocalSessionEntity("late", a, "book1", 0.0, 1.0, 1.0, 1, 2)) },
        )
        out.execute(keepDownloads = false)
        assertEquals(List(6) { false }, rows(a))
        assertFalse(db.accountDataDao().hasRows(a))
    }

    @Test fun theCallerGoingAway_midSignOut_stillFinishesIt() = runTest {
        seed(a)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val out = SignOut(
            accounts, Recording(data), recordingKept, { sid -> cancelled += sid; calls += "work" }, lastPlayed,
            pausePlayback = {}, stopPlayback = { calls += "stop"; gate.await() }, pushes = emptyList(), awaitOnline = { true },
            logout = { _, _ -> true }, clearAccountState = { calls += "state:$it" },
        )
        val job = launch { out.execute(keepDownloads = false) }
        testScheduler.runCurrent()
        assertEquals(listOf("stop"), calls)
        job.cancel() // the Settings ViewModel is cleared when the navigation pops it
        gate.complete(Unit)
        job.join()
        assertNull(accounts.acc.value)
        assertNull(accounts.refresh)
        assertEquals(List(6) { false }, rows(a))
        assertEquals("clear", calls.last())
    }

    @Test fun aDeviceTitleLastPlayed_isKept() = runTest {
        val device = LastPlayed(dev, localKey, 1)
        lastPlayed.value = device
        signOut().execute(keepDownloads = false)
        assertEquals(device, lastPlayed.value)
    }

    @Test fun logoutFailure_orTimeout_neverBlocksTheSignOut() = runTest {
        signOut(logout = { _, _ -> error("connection reset") }).execute(keepDownloads = false)
        assertNull(accounts.acc.value)

        accounts.acc.value = account; accounts.refresh = "refresh-2"
        signOut(logout = { _, _ -> awaitCancellation() }).execute(keepDownloads = false)
        assertNull(accounts.acc.value)
        assertTrue(testScheduler.currentTime <= SignOut.LOGOUT_TIMEOUT_MS)
    }

    @Test fun noRefreshToken_noLogoutCall() = runTest {
        accounts.refresh = null
        signOut().execute(keepDownloads = false)
        assertEquals(emptyList<String>(), logouts)
        assertNull(accounts.acc.value)
    }

    @Test fun aFailingStep_stillClearsTheCredentials() = runTest {
        val broken = DeviceAccountData(db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), DownloadFiles(root, db.audioFileDao()), InMemorySyncStamps(), { error("disk") })
        seed(a)
        signOut(purgeData = broken).execute(keepDownloads = false)
        assertNull(accounts.acc.value)
        assertNull(accounts.refresh)
        // The data is not lost to nowhere: the next app start finishes the purge.
        assertEquals(setOf(a), kept.pending.value)
        assertEquals(emptyList<KeptAccount>(), kept.list.value)
    }

    @Test fun withoutAccount_nothingHappens() = runTest {
        accounts.acc.value = null
        signOut().execute(keepDownloads = false)
        assertNull(signOut().check())
        assertEquals(emptyList<String>(), calls)
        assertNull(accounts.clearedKeeping)
    }

    @Test fun check_pausesThenPushesPendingChanges_andReportsWhatIsLeft() = runTest {
        seed(a) // dirty progress + a pending bookmark
        val pushed = mutableListOf<String>()
        val check = signOut(pushes = listOf(
            { sid -> calls += "push"; pushed += sid; db.progressDao().markSynced(sid, "book1", 1) },
            { sid -> pushed += "bookmarks:$sid" },
        )).check()

        assertEquals(listOf("pause", "push"), calls)
        assertEquals(listOf(a, "bookmarks:$a"), pushed)
        assertNotNull(check)
        assertEquals(1, check!!.unsynced) // the bookmark is still pending
        assertEquals(100L, check.downloadBytes)
    }

    @Test fun check_nothingPending_pushesNothing() = runTest {
        var pushes = 0
        val check = signOut(pushes = listOf({ _ -> pushes++ })).check()!!
        assertEquals(0, pushes)
        assertEquals(SignOutCheck(0, 0), check)
    }

    @Test fun check_offline_orFailing_orHanging_stillAnswers() = runTest {
        seed(a)
        var pushes = 0
        assertEquals(2, signOut(online = { false }, pushes = listOf({ _ -> pushes++ })).check()!!.unsynced)
        assertEquals(0, pushes)
        assertEquals(2, signOut(pushes = listOf({ _ -> error("500") }, { _ -> pushes++ })).check()!!.unsynced)
        assertEquals(1, pushes) // one failing sync does not stop the other
        val started = testScheduler.currentTime
        assertEquals(2, signOut(pushes = listOf({ _ -> awaitCancellation() })).check()!!.unsynced)
        assertTrue(testScheduler.currentTime - started <= SignOut.PUSH_TIMEOUT_MS)
        assertEquals(account, accounts.account.first()) // checking never signs out
    }
}
