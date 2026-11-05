package io.wolftown.lauschig.download

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Task 1.4 of Phase 7: only what the switch paused goes on, under the usual rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwitchedDownloadsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queued = mutableListOf<Triple<String, String, NetworkType?>>()
    private var failing = emptySet<String>()
    private val manager = object : DownloadManager {
        override suspend fun enqueue(serverId: String, itemId: String, network: NetworkType?) {
            if (itemId in failing) throw IOException("work manager unavailable")
            queued += Triple(serverId, itemId, network)
        }
        override suspend fun cancel(serverId: String, itemId: String, deleteFiles: Boolean) {}
        override fun observe(serverId: String, itemId: String) = flowOf<DownloadProgress?>(null)
    }
    private val file = File(ctx.cacheDir, "switched-${System.nanoTime()}.preferences_pb")
    private val store = PreferenceDataStoreFactory.create(scope = scope) { file }
    private val switched = SwitchedDownloads(store, db.downloadDao(), manager)

    @After fun tearDown() {
        scope.cancel()
        db.close()
        file.delete()
    }

    private suspend fun row(server: String, id: String, state: DownloadState, error: String? = null) =
        db.downloadDao().upsert(DownloadEntity(server, id, state, 1, error))

    private suspend fun leave(server: String) {
        switched.rememberActive(server)
        db.accountDataDao().pauseActiveDownloads(server)
    }

    @Test fun onlyWhatTheSwitchPaused_goesOn_withoutAnOverride_soTheWifiRuleApplies() = runBlocking {
        row(a, "running", DownloadState.RUNNING)
        row(a, "waiting", DownloadState.QUEUED)
        row(a, "pausedByUser", DownloadState.PAUSED)
        row(a, "pausedForStream", DownloadState.PAUSED, DownloadWorker.STREAM_PRIORITY)
        row(a, "done", DownloadState.COMPLETE)
        row(a, "failed", DownloadState.FAILED)
        leave(a)
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(a, "running")!!.state)

        switched.resume(a)

        assertEquals(setOf(Triple(a, "running", null), Triple(a, "waiting", null)), queued.toSet())
        assertEquals(DownloadState.PAUSED, db.downloadDao().get(a, "pausedByUser")!!.state)
        assertEquals(DownloadWorker.STREAM_PRIORITY, db.downloadDao().get(a, "pausedForStream")!!.error)
    }

    @Test fun eachAccountHasItsOwnNote() = runBlocking {
        row(a, "x", DownloadState.QUEUED)
        row(b, "y", DownloadState.QUEUED)
        leave(a)
        switched.resume(b) // B's turn: nothing of A's goes
        assertEquals(emptyList<Triple<String, String, NetworkType?>>(), queued)
        switched.resume(a)
        assertEquals(listOf(Triple(a, "x", null)), queued)
    }

    @Test fun aNoteGoesOnOnce() = runBlocking {
        row(a, "x", DownloadState.QUEUED)
        leave(a)
        switched.resume(a)
        switched.resume(a)
        assertEquals(1, queued.size)
    }

    /** Review M1: a process that dies after the note, before the pause: the rows still say waiting or running, their work is gone. */
    @Test fun notedRowsThatNeverGotPaused_goOnToo() = runBlocking {
        row(a, "running", DownloadState.RUNNING)
        row(a, "waiting", DownloadState.QUEUED)
        switched.rememberActive(a)
        switched.resume(a)
        assertEquals(setOf(Triple(a, "running", null), Triple(a, "waiting", null)), queued.toSet())
    }

    @Test fun aDownloadThatIsNoLongerPaused_orGone_isLeftAlone() = runBlocking {
        row(a, "doneMeanwhile", DownloadState.QUEUED)
        row(a, "failedMeanwhile", DownloadState.QUEUED)
        row(a, "removedMeanwhile", DownloadState.QUEUED)
        leave(a)
        row(a, "doneMeanwhile", DownloadState.COMPLETE)
        row(a, "failedMeanwhile", DownloadState.FAILED)
        db.downloadDao().delete(a, "removedMeanwhile")
        switched.resume(a)
        assertEquals(emptyList<Triple<String, String, NetworkType?>>(), queued)
        assertEquals(null, db.downloadDao().get(a, "removedMeanwhile"))
    }

    @Test fun whatCouldNotBeQueued_staysNoted_forTheNextStart() = runBlocking {
        row(a, "x", DownloadState.QUEUED)
        row(a, "y", DownloadState.QUEUED)
        leave(a)
        failing = setOf("y")
        switched.resume(a)
        assertEquals(listOf("x"), queued.map { it.second })
        failing = emptySet()
        switched.resume(a)
        assertEquals(listOf("x", "y"), queued.map { it.second })
    }

    @Test fun theNoteOutlivesTheProcess_andForgettingEndsIt() = runBlocking {
        row(a, "x", DownloadState.QUEUED)
        leave(a)
        SwitchedDownloads(store, db.downloadDao(), manager).resume(a) // another instance reads the same file
        assertEquals(1, queued.size)
        row(a, "y", DownloadState.QUEUED)
        leave(a)
        switched.forget(a)
        switched.resume(a)
        assertEquals(1, queued.size)
    }
}
