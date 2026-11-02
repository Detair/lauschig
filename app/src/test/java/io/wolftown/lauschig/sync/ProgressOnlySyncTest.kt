package io.wolftown.lauschig.sync

import android.content.Context
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.StubMeApi
import io.wolftown.lauschig.absapi.auth.AbsUser
import io.wolftown.lauschig.absapi.me.DeviceInfoDto
import io.wolftown.lauschig.absapi.me.LocalSessionsBody
import io.wolftown.lauschig.absapi.me.LocalSessionsResult
import io.wolftown.lauschig.absapi.me.ProgressUpdate
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.ProgressSyncOnPause
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressOnlySyncTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val s = "aaaaaaaaaaaaaaaa"
    private lateinit var db: LauschigDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun pauseAndTitleEnd_startAProgressSync_butNeverForADeviceTitle() {
        var syncs = 0
        var current: Pair<String, String>? = s to "li_1"
        val l = ProgressSyncOnPause({ current }) { syncs++ }
        l.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(0, syncs)
        l.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(1, syncs)
        l.onPlaybackStateChanged(Player.STATE_BUFFERING)
        assertEquals(1, syncs)
        l.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(2, syncs)
        current = DeviceLibrary.SERVER_ID to DeviceLibrary.titleId("content://tree/x", "dir:a")
        l.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        l.onPlaybackStateChanged(Player.STATE_ENDED)
        current = null
        l.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(2, syncs)
    }

    @Test fun enqueue_isOneDebouncedWorkNeedingANetwork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val wm = WorkManager.getInstance(ctx)
        SyncScheduler.enqueueProgressSync(wm)
        SyncScheduler.enqueueProgressSync(wm)
        val waiting = wm.getWorkInfosForUniqueWork(SyncScheduler.PROGRESS).get().filter { !it.state.isFinished }
        assertEquals(1, waiting.size)
        assertEquals(NetworkType.CONNECTED, waiting.single().constraints.requiredNetworkType)
        assertEquals(SyncScheduler.PROGRESS_DEBOUNCE_MS, waiting.single().initialDelayMillis)
    }

    @Test fun worker_syncsProgressOnly() = runBlocking {
        val calls = mutableListOf<String>()
        val me = object : StubMeApi() {
            override suspend fun me() = AbsUser("u", "n", "user").also { calls += "me" }
            override suspend fun updateProgress(itemId: String, u: ProgressUpdate) { calls += "progress $itemId" }
            override suspend fun updateEpisodeProgress(itemId: String, episodeId: String, u: ProgressUpdate) = error("unused")
            override suspend fun syncLocalSessions(body: LocalSessionsBody) = LocalSessionsResult(emptyList()).also { calls += "sessions" }
        }
        db.progressDao().saveLocal(s, "li_1", 10.0, 100.0, false, 5)
        val store = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
        val bus = PositionJumpBus()
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = ProgressSyncWorker(
                c, p, store, ProgressSync(flowOf(me), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") }), bus, { true },
            )
        }
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<ProgressSyncWorker>(ctx).setWorkerFactory(factory).build().doWork())
        assertEquals(listOf("me", "progress li_1", "me"), calls)
        assertFalse(db.progressDao().get(s, "li_1")!!.dirty)
        val offline = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) = ProgressSyncWorker(
                c, p, store, ProgressSync(flowOf(me), db.progressDao(), db.sessionDao(), db.itemDao(), db.episodeDao(), { DeviceInfoDto(deviceId = "d") }), bus, { false },
            )
        }
        assertEquals(ListenableWorker.Result.retry(), TestListenableWorkerBuilder<ProgressSyncWorker>(ctx).setWorkerFactory(offline).build().doWork())
    }
}
