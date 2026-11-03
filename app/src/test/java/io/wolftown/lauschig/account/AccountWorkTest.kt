package io.wolftown.lauschig.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import io.wolftown.lauschig.download.DefaultDownloadManager
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

class IdleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork() = Result.success()
}

/** Runs until it is stopped, like a download in the middle of a file. */
class BlockingWorker(context: Context, params: WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        started.complete(Unit)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            stopped.complete(Unit)
        }
    }

    companion object {
        var started = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
    }
}

/** Sign-out cancels the account's sync and download work; another server's downloads and device work stay. */
@RunWith(RobolectricTestRunner::class)
class AccountWorkTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        wm = WorkManager.getInstance(ctx)
    }

    /** Waits an hour, so it stays ENQUEUED in the test. */
    private fun waiting(tag: String? = null) =
        OneTimeWorkRequestBuilder<IdleWorker>().setInitialDelay(1, TimeUnit.HOURS).apply { if (tag != null) addTag(tag) }.build()

    private fun state(name: String): WorkInfo.State? = wm.getWorkInfosForUniqueWork(name).get().lastOrNull()?.state

    @Test fun cancelsTheSyncsAndThisServersDownloads_only() = runBlocking {
        listOf(SyncScheduler.NOW, SyncScheduler.PROGRESS, SyncScheduler.BOOKMARKS, SyncScheduler.AFTER_SCAN, SyncScheduler.ON_ONLINE)
            .forEach { wm.enqueueUniqueWork(it, ExistingWorkPolicy.REPLACE, waiting()) }
        wm.enqueueUniquePeriodicWork(
            SyncScheduler.PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IdleWorker>(15, TimeUnit.MINUTES).setInitialDelay(1, TimeUnit.HOURS).build(),
        )
        // Known from the download rows, and one only the server tag knows about (no row any more).
        wm.enqueueUniqueWork(DefaultDownloadManager.workName(a, "book1"), ExistingWorkPolicy.KEEP, waiting())
        wm.enqueueUniqueWork(DefaultDownloadManager.workName(a, "pod~ep1"), ExistingWorkPolicy.KEEP, waiting(DefaultDownloadManager.serverTag(a)))
        wm.enqueueUniqueWork(DefaultDownloadManager.workName(b, "book1"), ExistingWorkPolicy.KEEP, waiting(DefaultDownloadManager.serverTag(b)))
        wm.enqueueUniqueWork("local-scan", ExistingWorkPolicy.KEEP, waiting())
        wm.enqueueUniqueWork("cleanup-reminder", ExistingWorkPolicy.KEEP, waiting())

        WorkManagerAccountWork({ wm }, { sid -> if (sid == a) listOf("book1") else emptyList() }, waitMs = 1_000).cancel(a)

        SyncScheduler.ACCOUNT_WORK.forEach { assertEquals(it, WorkInfo.State.CANCELLED, state(it)) }
        assertEquals(WorkInfo.State.CANCELLED, state(DefaultDownloadManager.workName(a, "book1")))
        assertEquals(WorkInfo.State.CANCELLED, state(DefaultDownloadManager.workName(a, "pod~ep1")))
        assertEquals(WorkInfo.State.ENQUEUED, state(DefaultDownloadManager.workName(b, "book1")))
        assertEquals(WorkInfo.State.ENQUEUED, state("local-scan"))
        assertEquals(WorkInfo.State.ENQUEUED, state("cleanup-reminder"))
    }

    @Test fun aRunningDownload_isStopped() = runBlocking {
        BlockingWorker.started = kotlinx.coroutines.CompletableDeferred()
        BlockingWorker.stopped = kotlinx.coroutines.CompletableDeferred()
        val name = DefaultDownloadManager.workName(a, "book1")
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BlockingWorker>().addTag(DefaultDownloadManager.serverTag(a)).build())
        kotlinx.coroutines.withTimeout(10_000) { BlockingWorker.started.await() }
        assertEquals(WorkInfo.State.RUNNING, state(name))

        WorkManagerAccountWork({ wm }, { listOf("book1") }, waitMs = 5_000).cancel(a)

        kotlinx.coroutines.withTimeout(10_000) { BlockingWorker.stopped.await() } // its coroutine was cancelled
        assertEquals(WorkInfo.State.CANCELLED, state(name))
    }

    @Test fun theNextSignInSchedulesThePeriodicSyncAgain() {
        wm.enqueueUniquePeriodicWork(
            SyncScheduler.PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IdleWorker>(15, TimeUnit.MINUTES).setInitialDelay(1, TimeUnit.HOURS).build(),
        )
        runBlocking { WorkManagerAccountWork({ wm }, { emptyList() }, waitMs = 1_000).cancel(a) }
        assertEquals(WorkInfo.State.CANCELLED, state(SyncScheduler.PERIODIC))

        val api = ApiProvider(flowOf(null), OkHttpClient(), FakeAccountStore(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        SyncScheduler(ctx, api).syncNow() // what a sign-in calls

        assertEquals(WorkInfo.State.ENQUEUED, state(SyncScheduler.PERIODIC))
    }
}
