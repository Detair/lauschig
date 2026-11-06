package io.wolftown.lauschig.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadFiles
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.LastPlayed
import io.wolftown.lauschig.playback.FakeLastPlayedStore
import io.wolftown.lauschig.sync.InMemorySyncStamps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private val lastPlayed = FakeLastPlayedStore().apply { onForget = { calls += "lastPlayed" } }

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
        stopPlayback: suspend () -> Unit = { calls += "stop" },
        logoutProfile: suspend (Account, String) -> Boolean = logout,
        stopAgain: suspend () -> Unit = {},
    ) = SignOut(
        accounts = accounts,
        data = Recording(purgeData),
        kept = recordingKept,
        work = recordingWork { sid -> cancelled += sid; calls += "work" },
        lastPlayed = lastPlayed,
        pausePlayback = { calls += "pause" },
        stopPlayback = stopPlayback,
        pushes = pushes,
        awaitOnline = online,
        logout = logout,
        clearAccountState = { sid -> calls += "state:$sid" },
        setAsideState = { sid -> calls += "aside:$sid" },
        forgetSavedState = { sid -> calls += "forgetState:$sid" },
        noteDownloads = { sid -> calls += "note:$sid" },
        resumeDownloads = { sid -> calls += "resume:$sid" },
        forgetDownloads = { sid -> calls += "forgetNote:$sid" },
        syncNow = { calls += "syncNow" },
        showLastTitle = { sid -> calls += "lastTitle:$sid" },
        logoutProfile = logoutProfile,
        stopAgain = stopAgain,
    )

    /** [cancelOwn] calls of the account work (a removed profile's own work). */
    private val cancelledOwn = mutableListOf<String>()

    /** Records [AccountWork.cancel] with [onCancel] and [AccountWork.cancelOwn] in [cancelledOwn]: the two differ. */
    private fun recordingWork(onCancel: (String) -> Unit) = object : AccountWork {
        override suspend fun cancel(serverId: String) = onCancel(serverId)
        override suspend fun cancelOwn(serverId: String) { cancelledOwn += serverId }
    }

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
        assertEquals(listOf("stop", "work", "purge", "forget", "lastPlayed", "forgetNote:$a", "state:$a", "rowsAgain", "clear", "rowsAgain"), calls - "logout")
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
        assertEquals(listOf("stop", "work", "pauseDownloads", "remember", "lastPlayed", "forgetNote:$a", "state:$a", "clear"), calls - "logout")
        // Kept means kept: the sync stamps and the car's covers of the account stay too.
        assertEquals(7L, stamps.get("progress_sync:$a"))
        assertEquals(emptyList<String>(), forgottenCovers)
        assertEquals(listOf(a), cancelled)
        assertEquals(listOf("refresh-1"), logouts)
        assertNull(lastPlayed.value) // a play key cannot start the old account's title
    }

    @Test fun switchTo_keepsTheOldAccount_andReplacesItWithoutEverClearing() = runTest {
        seed(a)
        db.downloadDao().upsert(DownloadEntity(a, "book2", DownloadState.QUEUED, 1))
        lastPlayed.value = LastPlayed(a, "book1", 1)
        val next = Account(b, "u2", "lukas", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        val seen = mutableListOf<String?>()
        val watching = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            accounts.acc.collect { seen += it?.serverId }
        }

        signOut().switchTo(next, "access-2", "refresh-2")

        // As „Abmelden“ with „Behalten“: rows, files and a paused download stay, the work stops, the account is kept ...
        assertEquals(List(6) { true }, rows(a))
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(a, "book2")!!.state)
        assertEquals(listOf(KeptAccount(a, "fabian", "abs.example.org", userId = "u1")), kept.list.value)
        assertEquals(listOf(a), cancelled)
        assertEquals(listOf("refresh-1"), logouts)
        assertNull(lastPlayed.value)
        // ... but the last step stores the next account instead of clearing: never "no account" in between.
        assertEquals(listOf("stop", "work", "pauseDownloads", "remember", "lastPlayed", "forgetNote:$a", "state:$a", "resume:$b"), calls - "logout")
        assertNull(accounts.clearedKeeping)
        assertEquals(next, accounts.acc.value)
        assertEquals("access-2", accounts.access)
        assertEquals("refresh-2", accounts.refresh)
        assertEquals(listOf<String?>(a, b), seen)
        watching.cancel()
    }

    @Test fun switchTo_withoutAccount_justSaves() = runTest {
        accounts.acc.value = null
        val next = Account(b, "u2", "lukas", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())
        signOut().switchTo(next, "access-2", "refresh-2")
        assertEquals(next, accounts.acc.value)
        assertEquals(listOf("resume:$b"), calls)
        assertTrue(cancelled.isEmpty())
    }

    // Profile switch (Phase 6 seam, Task 12.1): the same steps, but the account stays signed in.

    private val lukas = Account(b, "u2", "lukas", false, ServerAddresses(null, "https://abs.example.org".toHttpUrl()), emptySet())

    @Test fun switchProfile_setsTheAccountAside_keepsItsData_neverLogsOut() = runTest {
        seed(a)
        db.downloadDao().upsert(DownloadEntity(a, "book2", DownloadState.QUEUED, 1))
        lastPlayed.value = LastPlayed(a, "book1", 1)
        kept.pending.value = setOf(a) // a stale purge must never run on data that stays
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        accounts.onSwitch = { calls += "switch" }
        val seen = mutableListOf<String?>()
        val watching = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            accounts.acc.collect { seen += it?.serverId }
        }

        assertTrue(signOut().switchProfile(b))

        // Like „Behalten“: rows, files and a paused download stay, the work stops, its last title waits for it ...
        assertEquals(List(6) { true }, rows(a))
        assertTrue(File(root, "$a/book1/0.mp3").isFile)
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(a, "book2")!!.state)
        assertEquals(listOf(a), cancelled)
        assertEquals(LastPlayed(a, "book1", 1), lastPlayed.get(a))
        // ... but it stays signed in: no logout, nobody is asked to delete it, its tokens wait in the store.
        assertEquals(emptyList<String>(), logouts)
        assertEquals(emptyList<KeptAccount>(), kept.list.value)
        assertEquals(emptySet<String>(), kept.pending.value)
        assertEquals(
            listOf("pause", "stop", "note:$a", "work", "note:$a", "pauseDownloads", "forget", "aside:$a", "switch", "resume:$b", "lastTitle:$b", "syncNow"),
            calls,
        )
        assertNull(accounts.clearedKeeping)
        assertEquals(lukas, accounts.acc.value)
        assertEquals("access-2", accounts.access)
        assertEquals(listOf(FakeAccountStore.Aside(account, "access-1", "refresh-1", false)), accounts.aside.value)
        // Never "no account" in between.
        assertEquals(listOf<String?>(a, b), seen)
        watching.cancel()
    }

    @Test fun switchProfile_toAnAccountThatIsNotSaved_touchesNothing() = runTest {
        seed(a)
        assertFalse(signOut().switchProfile(b))
        assertTrue(calls.isEmpty())
        assertTrue(cancelled.isEmpty())
        assertEquals(account, accounts.acc.value)
    }

    @Test fun switchProfile_whileSignedOut_justActivates() = runTest {
        accounts.acc.value = null
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        assertTrue(signOut().switchProfile(b))
        assertEquals(lukas, accounts.acc.value)
        assertEquals("nothing to leave; it arrives like every switch", listOf("resume:$b", "lastTitle:$b", "syncNow"), calls)
    }

    // Task 1.3: before the switch the position is final and the changes go up, at most 5 s.

    private suspend fun aSavedProfile() {
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        accounts.onSwitch = { calls += "switch" }
    }

    private val pushLog: List<suspend (String) -> Unit> = listOf({ sid -> calls += "push:$sid" }, { sid -> calls += "push2:$sid" })

    @Test fun switchProfile_pausesFirst_pushesTheChanges_andOnlyThenLeaves() = runTest {
        seed(a) // a dirty progress row
        aSavedProfile()
        assertTrue(signOut(pushes = pushLog).switchProfile(b))
        assertEquals(listOf("pause", "push:$a", "push2:$a", "stop"), calls.take(4))
        assertTrue(calls.indexOf("push2:$a") < calls.indexOf("switch"))
    }

    @Test fun addProfile_pausesFirst_pushesTheChanges_andOnlyThenLeaves() = runTest {
        seed(a)
        accounts.onSwitch = { calls += "switch" }
        signOut(pushes = pushLog).addProfile(lukas, "access-2", "refresh-2")
        assertEquals(listOf("pause", "push:$a", "push2:$a", "stop"), calls.take(4))
        assertEquals(lukas, accounts.acc.value)
        assertEquals(listOf("switch", "resume:$b", "lastTitle:$b", "syncNow"), calls.takeLast(4))
    }

    @Test fun switchProfile_offline_stillSwitches_theChangesStayInRoom() = runTest {
        seed(a)
        aSavedProfile()
        assertTrue(signOut(online = { false }, pushes = pushLog).switchProfile(b))
        assertFalse(calls.any { it.startsWith("push") })
        assertEquals(lukas, accounts.acc.value)
        assertEquals("the change waits for its account", 1, db.progressDao().dirty(a).size)
    }

    @Test fun switchProfile_aHangingPush_orConnectionCheck_costsAtMostFiveSeconds() = runTest {
        seed(a)
        aSavedProfile()
        assertTrue(signOut(pushes = listOf({ _ -> kotlinx.coroutines.awaitCancellation() })).switchProfile(b))
        assertEquals(SignOut.SWITCH_PUSH_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(5_000L, SignOut.SWITCH_PUSH_TIMEOUT_MS)
        assertEquals(lukas, accounts.acc.value)

        calls.clear()
        val start = testScheduler.currentTime
        accounts.acc.value = account
        aSavedProfile()
        assertTrue(signOut(online = { kotlinx.coroutines.awaitCancellation() }, pushes = pushLog).switchProfile(b))
        assertEquals(5_000L, testScheduler.currentTime - start)
        assertEquals(lukas, accounts.acc.value)
    }

    @Test fun switchProfile_aFailingPush_stillSwitches_theOtherPushStillRuns() = runTest {
        seed(a)
        aSavedProfile()
        val pushes: List<suspend (String) -> Unit> = listOf({ _ -> throw java.io.IOException("server gone") }, { sid -> calls += "push2:$sid" })
        assertTrue(signOut(pushes = pushes).switchProfile(b))
        assertTrue("push2:$a" in calls)
        assertEquals(lukas, accounts.acc.value)
    }

    @Test fun switchProfile_nothingToPush_doesNotWaitForTheConnection() = runTest {
        aSavedProfile()
        var asked = 0
        assertTrue(signOut(online = { asked++; true }, pushes = pushLog).switchProfile(b))
        assertEquals(0, asked)
        assertFalse(calls.any { it.startsWith("push") })
    }

    // Review I1: what started between the first stop and the account's change does not survive it.

    @Test fun afterTheAccountChanged_whatStartedMeanwhileIsStopped_beforeTheNextAccountArrives() = runTest {
        aSavedProfile()
        assertTrue(signOut(stopAgain = { calls += "stopAgain" }).switchProfile(b))
        assertEquals(listOf("switch", "stopAgain", "resume:$b", "lastTitle:$b", "syncNow"), calls.takeLast(5))
    }

    @Test fun afterASignOut_whatStartedMeanwhileIsStopped_too() = runTest {
        signOut(stopAgain = { calls += "stopAgain" }).execute(keepDownloads = true)
        assertEquals(listOf("clear", "stopAgain"), calls.takeLast(2))
    }

    /** Review N5: the tracker's last save of a title that only the last stop ended; the rows of a purged account stay gone. */
    @Test fun aTitleStoppedOnlyAtTheLastStop_leavesNoRowsAfterAPurge() = runTest {
        seed(a)
        val late: suspend () -> Unit = { db.progressDao().saveLocal(a, "book1", 42.0, 100.0, false, 9) }
        signOut(stopAgain = late).execute(keepDownloads = false)
        assertNull(db.progressDao().get(a, "book1"))
        assertFalse(data.hasRows(a))
    }

    @Test fun keeping_theRowsOfTheLastStopStay() = runTest {
        seed(a)
        val late: suspend () -> Unit = { db.progressDao().saveLocal(a, "book1", 42.0, 100.0, false, 9) }
        signOut(stopAgain = late).execute(keepDownloads = true)
        assertEquals(42.0, db.progressDao().get(a, "book1")!!.currentTimeSec, 0.0)
    }

    // Review M6: a hanging stop does not hold every account operation.

    @Test fun aStopThatHangs_costsAtMostItsLimit_theSwitchGoesOn() = runTest {
        aSavedProfile()
        // A long delay, no real hang: inside NonCancellable nothing could end the test if the limit were missing.
        assertTrue(signOut(stopPlayback = { kotlinx.coroutines.delay(3_600_000) }, stopAgain = { kotlinx.coroutines.delay(3_600_000) }).switchProfile(b))
        assertEquals(2 * SignOut.STOP_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(5_000L, SignOut.STOP_TIMEOUT_MS)
        assertEquals(lukas, accounts.acc.value)
    }

    // Task 1.4 and failures: what arrives, what stays.

    @Test fun switchProfile_aFailedActivation_keepsTheOldAccount_andItGoesOn() = runTest {
        seed(a)
        aSavedProfile()
        accounts.onSwitch = { throw java.io.IOException("disk full") }
        assertFalse(signOut().switchProfile(b))
        assertEquals(account, accounts.acc.value)
        assertEquals(listOf("resume:$a", "lastTitle:$a", "syncNow"), calls.takeLast(3))
    }

    @Test fun addProfile_aFailedSave_keepsTheOldAccount_itGoesOn_andTheCallerHearsOfIt() = runTest {
        seed(a)
        accounts.onSwitch = { throw java.io.IOException("disk full") }
        val failed = runCatching { signOut().addProfile(lukas, "access-2", "refresh-2") }.exceptionOrNull()
        assertTrue(failed is java.io.IOException)
        assertEquals(account, accounts.acc.value)
        assertEquals(listOf("resume:$a", "lastTitle:$a", "syncNow"), calls.takeLast(3))
    }

    @Test fun signIn_ofAProfileThatWasSetAside_itsDownloadsGoOn() = runTest {
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", true))
        signOut().signIn(lukas, "access-3", "refresh-3") // e.g. its token ran out; the switched account signs in again
        assertTrue("resume:$b" in calls)
        assertEquals(lukas, accounts.acc.value)
    }

    @Test fun aSignOut_forgetsWhatASwitchNoted_andNotesNothing() = runTest {
        seed(a)
        signOut().execute(keepDownloads = true)
        assertTrue("forgetNote:$a" in calls)
        assertFalse(calls.any { it.startsWith("note:") || it.startsWith("resume:") || it.startsWith("aside:") })
    }

    @Test fun addProfile_setsTheActiveAccountAside_andSignsInTheNext() = runTest {
        seed(a)
        lastPlayed.value = LastPlayed(a, "book1", 1)
        accounts.onSwitch = { calls += "switch" }
        signOut().addProfile(lukas, "access-2", "refresh-2")
        assertEquals(List(6) { true }, rows(a))
        assertEquals(emptyList<String>(), logouts)
        assertEquals(LastPlayed(a, "book1", 1), lastPlayed.get(a))
        assertEquals(
            listOf("pause", "stop", "note:$a", "work", "note:$a", "pauseDownloads", "forget", "aside:$a", "switch", "resume:$b", "lastTitle:$b", "syncNow"),
            calls,
        )
        assertEquals(lukas, accounts.acc.value)
        assertEquals(listOf(a), accounts.aside.value.map { it.account.serverId })
    }

    @Test fun addProfile_ofTheSameAccount_justSavesItsNewTokens() = runTest {
        signOut().addProfile(account, "access-9", "refresh-9")
        assertTrue(calls.isEmpty())
        assertEquals("access-9", accounts.access)
        assertEquals(emptyList<FakeAccountStore.Aside>(), accounts.aside.value)
    }

    // Fix round 1, E7: removing a saved profile purges like a sign-out without keeping – never ownerless data.

    @Test fun removeProfile_logsOut_purges_andForgets_onlyThatAccount() = runTest {
        seed(a); seed(b)
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        var owedWhenForgotten = false
        accounts.onForget = { owedWhenForgotten = b in kept.pending.value }

        assertTrue(signOut().removeProfile(b))

        assertTrue("the purge is owed before the account is forgotten", owedWhenForgotten)
        assertEquals(List(6) { false }, rows(b))
        assertFalse(File(root, b).exists())
        assertEquals(listOf("refresh-2"), logouts)
        // Its own work only; the signed-in account's syncs are not touched (WorkManager side: AccountWorkTest).
        assertEquals(listOf(b), cancelledOwn)
        assertEquals(emptyList<String>(), cancelled)
        assertEquals(emptyList<FakeAccountStore.Aside>(), accounts.aside.value)
        assertEquals(emptySet<String>(), kept.pending.value)
        // The signed-in account and its data are untouched.
        assertEquals(account, accounts.acc.value)
        assertEquals(List(6) { true }, rows(a))
        assertNull(accounts.clearedKeeping)
    }

    /** Review N3: the signed-in account has played nothing: its play key does not start what the removed profile heard. */
    @Test fun removeProfile_handsItsDeviceTitleToNobody() = runTest {
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        lastPlayed.set(LastPlayed(dev, localKey, 3), account = b)
        assertTrue(signOut().removeProfile(b))
        assertNull(lastPlayed.get(dev))
        assertNull(lastPlayed.deviceTitleOf(b))
    }

    @Test fun removeProfile_takesItsPerAccountState_notTheSignedInAccounts() = runTest {
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        lastPlayed.set(LastPlayed(a, "book1", 1))
        lastPlayed.set(LastPlayed(b, "book2", 2))
        lastPlayed.set(LastPlayed(dev, localKey, 3))
        assertTrue(signOut().removeProfile(b))
        assertTrue("forgetState:$b" in calls)
        assertTrue("forgetNote:$b" in calls)
        assertNull(lastPlayed.get(b))
        assertEquals(LastPlayed(a, "book1", 1), lastPlayed.get(a))
        assertEquals(LastPlayed(dev, localKey, 3), lastPlayed.get(dev))
        assertFalse(calls.any { it.startsWith("state:") || it.startsWith("aside:") || it == "syncNow" })
    }

    // Task 1.6: its own addresses, never decisive.

    @Test fun removeProfile_logsOutThroughThePathForAProfile_notTheSignedInAccountsConnection() = runTest {
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        val asked = mutableListOf<String>()
        val out = signOut(
            logout = { _, _ -> error("the path of the signed-in account") },
            logoutProfile = { who, token -> asked += "${who.serverId}:$token"; true },
        )
        assertTrue(out.removeProfile(b))
        assertEquals(listOf("$b:refresh-2"), asked)
    }

    @Test fun removeProfile_aServerThatDoesNotAnswer_doesNotStopTheRemoval() = runTest {
        seed(b)
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        val out = signOut(logoutProfile = { _, _ -> kotlinx.coroutines.awaitCancellation() })
        assertTrue(out.removeProfile(b))
        assertEquals(SignOut.LOGOUT_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(List(6) { false }, rows(b))
        assertEquals(emptyList<FakeAccountStore.Aside>(), accounts.aside.value)
    }

    @Test fun removeProfile_aFailingPurge_isLeftToTheNextStart() = runTest {
        seed(b)
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        val failing = object : AccountData by data {
            override suspend fun purge(serverId: String) = throw java.io.IOException("disk full")
        }
        assertTrue(signOut(purgeData = failing).removeProfile(b))
        assertEquals(setOf(b), kept.pending.value)
        assertEquals(emptyList<FakeAccountStore.Aside>(), accounts.aside.value)
    }

    @Test fun removeProfile_ofTheActiveOrAnUnknownAccount_touchesNothing() = runTest {
        seed(a)
        assertFalse(signOut().removeProfile(a))
        assertFalse(signOut().removeProfile(b))
        assertEquals(List(6) { true }, rows(a))
        assertTrue(calls.isEmpty())
        assertTrue(logouts.isEmpty())
        assertEquals(emptySet<String>(), kept.pending.value)
    }

    // Fix round 2, N2: account operations run one after the other – a profile that has just become the signed-in one
    // is never purged, and a removal never races a switch.

    @Test fun aSwitchToAProfile_thenItsRemoval_neverPurgesTheSignedInAccount() = runTest {
        seed(a); seed(b)
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val out = signOut(stopPlayback = { gate.await() }) // the switch holds on while stopping playback

        val switching = async { out.switchProfile(b) }
        val removing = async { out.removeProfile(b) }
        testScheduler.runCurrent()
        assertFalse("the removal waits for the switch", removing.isCompleted)
        gate.complete(Unit)

        assertTrue(switching.await())
        assertFalse(removing.await())
        assertEquals(lukas, accounts.acc.value)
        assertEquals(List(6) { true }, rows(b))
        assertTrue(File(root, "$b/book1/0.mp3").isFile)
        assertEquals(emptySet<String>(), kept.pending.value)
        assertEquals(emptyList<String>(), logouts)
    }

    @Test fun aRemoval_thenASwitchToTheSameProfile_findsNothingToSwitchTo() = runTest {
        seed(a); seed(b)
        accounts.aside.value = listOf(FakeAccountStore.Aside(lukas, "access-2", "refresh-2", false))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val out = signOut(logout = { _, token -> logouts += token; gate.await(); true }) // the removal holds on in its logout

        val removing = async { out.removeProfile(b) }
        val switching = async { out.switchProfile(b) }
        testScheduler.runCurrent()
        assertFalse("the switch waits for the removal", switching.isCompleted)
        gate.complete(Unit)

        assertTrue(removing.await())
        assertFalse(switching.await())
        assertEquals(account, accounts.acc.value)
        assertEquals(List(6) { false }, rows(b))
        assertEquals(List(6) { true }, rows(a))
        assertEquals(listOf("refresh-2"), logouts)
    }

    @Test fun aSignInWhileASignOutRuns_waitsForIt() = runTest {
        seed(a)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val out = signOut(stopPlayback = { gate.await() })
        val signingOut = async { out.execute(keepDownloads = false) }
        val signingIn = async { out.signIn(lukas, "access-2", "refresh-2") }
        testScheduler.runCurrent()
        assertFalse(signingIn.isCompleted)
        gate.complete(Unit)
        signingOut.await(); signingIn.await()
        // Signed out completely first (purge, clear), then signed in – not the other way round.
        assertEquals(lukas, accounts.acc.value)
        assertEquals("access-2", accounts.access)
        assertEquals(List(6) { false }, rows(a))
    }

    @Test fun signIn_anotherAccount_switches_theSameOne_justSaves() = runTest {
        seed(a)
        signOut().signIn(account, "access-9", "refresh-9")
        assertTrue(calls.isEmpty())
        assertEquals("access-9", accounts.access)
        signOut().signIn(lukas, "access-2", "refresh-2")
        // Like switchTo: the old one keeps its data and is remembered, never "no account" in between.
        assertEquals(listOf(KeptAccount(a, "fabian", "abs.example.org", userId = "u1")), kept.list.value)
        assertEquals(List(6) { true }, rows(a))
        assertEquals(lukas, accounts.acc.value)
        accounts.acc.value = null
        signOut().signIn(account, "access-1", "refresh-1")
        assertEquals(account, accounts.acc.value)
    }

    @Test fun moreChangesAfterStopping_thanShown_abortWithoutDeleting() = runTest {
        seed(a) // 2 unsynced: the dirty progress row and the pending bookmark
        val out = SignOut(
            accounts, Recording(data), recordingKept, recordingWork { calls += "work" }, lastPlayed,
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

    @Test fun downloadBytes_isAFileWalk_thatWorksWhenRoomDoesNot() = runTest {
        seed(a)
        db.close() // the database is gone: the check fails ...
        assertTrue(runCatching { signOut().check() }.isFailure)
        // ... but the downloads on disk are still named with their size.
        assertEquals(100L, signOut().downloadBytes())
        accounts.acc.value = null
        assertEquals(0L, signOut().downloadBytes())
    }

    @Test fun aLateWriteAfterThePurge_isDeletedToo() = runTest {
        seed(a)
        // The stopping service's last progress save lands after the purge (it runs on the service's own scope).
        val out = SignOut(
            accounts, Recording(data), recordingKept, recordingWork { calls += "work" }, lastPlayed,
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
            accounts, Recording(data), recordingKept, recordingWork { sid -> cancelled += sid; calls += "work" }, lastPlayed,
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
        assertEquals(listOf("clear", "rowsAgain"), calls.takeLast(2))
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

    /**
     * "Abmelden und löschen" takes the account's automatic clean-up with it: signed in again to the same server, it
     * starts like a new account – "Nie", no question left over (none settled into a keep entry at the next start), no
     * keep entries. Another account's are untouched.
     */
    @Test fun withoutKeeping_theAccountComesBackLikeANewOne_cleanupOff_nothingAskedOrKept(): Unit = runBlocking {
        val settings = io.wolftown.lauschig.download.DownloadSettings(ctx)
        val app = io.wolftown.lauschig.settings.AppSettings(ctx)
        val questionFile = File(root.parentFile, "questions.preferences_pb")
        val scopes = mutableListOf<CoroutineScope>()
        fun questions() = io.wolftown.lauschig.cleanup.FinishedQuestions(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }, produceFile = { questionFile }),
            autoDeleteOn = { sid -> settings.autoDeleteFinished(sid).first() != AutoDeleteMode.NEVER },
            keepEntry = { app.setCleanupIgnored(it, true) },
            deleteDownload = { _, _ -> },
        )
        try {
            val q = questions()
            settings.setAutoDeleteFinished(a, AutoDeleteMode.AFTER_3_DAYS)
            settings.setAutoDeleteFinished(b, AutoDeleteMode.IMMEDIATELY)
            app.setCleanupIgnored("$a:book1", true)
            app.setCleanupIgnored("$b:book1", true)
            q.ask(io.wolftown.lauschig.cleanup.FinishedQuestion(a, "book2")) // still open at the sign-out
            q.ask(io.wolftown.lauschig.cleanup.FinishedQuestion(b, "x"))
            seed(a)
            val purging = DeviceAccountData(
                db.accountDataDao(), db.progressDao(), db.sessionDao(), db.bookmarkDao(), DownloadFiles(root, db.audioFileDao()), stamps,
                forgottenCovers::add, forgetAutoDelete = io.wolftown.lauschig.cleanup.AutoDeleteAccountState(settings, q, app)::forget,
                forgetAudibleAnswer = app::clearMissingVolumes,
            )
            app.setMissingVolumesEnabled(a, true)
            app.setMissingVolumesEnabled(b, true)

            signOut(purgeData = purging).execute(keepDownloads = false)
            accounts.acc.value = account // signed in again to the same server

            assertEquals(AutoDeleteMode.NEVER, settings.autoDeleteFinished(a).first())
            assertEquals("a purge: it is asked again", true, app.missingVolumesUndecided(a).first())
            assertEquals("the other's stays", true, app.missingVolumesEnabled(b).first())
            assertEquals(setOf(io.wolftown.lauschig.cleanup.FinishedQuestion(b, "x")), q.holds.first())
            assertEquals(listOf(io.wolftown.lauschig.cleanup.FinishedQuestion(b, "x")), q.open.value)
            assertEquals(setOf("$b:book1"), app.cleanupIgnored.first())
            // The next start settles no question of it.
            scopes.forEach { s -> s.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() } }
            questions().settleLeftovers()
            assertEquals(setOf("$b:book1", "$b:x"), app.cleanupIgnored.first())
            assertEquals(AutoDeleteMode.IMMEDIATELY, settings.autoDeleteFinished(b).first())
        } finally {
            scopes.forEach { it.cancel() }
            settings.setAutoDeleteFinished(a, AutoDeleteMode.NEVER)
            settings.setAutoDeleteFinished(b, AutoDeleteMode.NEVER)
            app.cleanupIgnored.first().forEach { app.setCleanupIgnored(it, false) }
        }
    }
}
