package io.wolftown.lauschig.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloadManagerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private lateinit var mgr: DefaultDownloadManager

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val files = DownloadFiles(File(ctx.cacheDir, "dm-test-${System.nanoTime()}"), db.audioFileDao())
        mgr = DefaultDownloadManager(ctx, db.downloadDao(), db.audioFileDao(), files, DownloadSettings(ctx))
    }

    @After fun tearDown() = db.close()

    private fun waiting(): WorkInfo =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("download-$s-it").get().single { !it.state.isFinished }

    @Test fun override_setsTheNetworkConstraint() = runBlocking {
        mgr.enqueue(s, "it", NetworkType.CONNECTED)
        assertEquals(NetworkType.CONNECTED, waiting().constraints.requiredNetworkType)
        assertEquals(DownloadState.QUEUED, db.downloadDao().get(s, "it")!!.state)
    }

    @Test fun override_replacesWaitingWorkWithAnotherNetwork() = runBlocking {
        mgr.enqueue(s, "it", NetworkType.UNMETERED)
        val first = waiting()
        mgr.enqueue(s, "it", NetworkType.CONNECTED)
        val second = waiting()
        assertNotEquals(first.id, second.id)
        assertEquals(NetworkType.CONNECTED, second.constraints.requiredNetworkType)
    }

    @Test fun sameNetwork_orNoOverride_keepsWaitingWork() = runBlocking {
        mgr.enqueue(s, "it", NetworkType.UNMETERED)
        val first = waiting()
        mgr.enqueue(s, "it", NetworkType.UNMETERED)
        mgr.enqueue(s, "it")
        assertEquals(first.id, waiting().id)
    }

    @Test fun replaceDecision_onlyForWaitingWork() {
        assertTrue(DefaultDownloadManager.replaces(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED, NetworkType.CONNECTED))
        assertTrue(DefaultDownloadManager.replaces(WorkInfo.State.BLOCKED, NetworkType.UNMETERED, NetworkType.CONNECTED))
        assertFalse(DefaultDownloadManager.replaces(WorkInfo.State.RUNNING, NetworkType.UNMETERED, NetworkType.CONNECTED))
        assertFalse(DefaultDownloadManager.replaces(WorkInfo.State.ENQUEUED, NetworkType.CONNECTED, NetworkType.CONNECTED))
        assertFalse(DefaultDownloadManager.replaces(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED, null))
        assertFalse(DefaultDownloadManager.replaces(null, NetworkType.UNMETERED, NetworkType.CONNECTED))
    }
}
