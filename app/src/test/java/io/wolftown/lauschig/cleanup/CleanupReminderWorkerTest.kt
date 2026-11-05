package io.wolftown.lauschig.cleanup

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.DownloadEntity
import io.wolftown.lauschig.db.entities.DownloadState
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.db.entities.ProgressEntity
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The weekly reminder names finished downloads – unless they are deleted automatically anyway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CleanupReminderWorkerTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LauschigDatabase
    private val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private lateinit var downloadSettings: DownloadSettings

    @Before fun setUp(): Unit = runBlocking {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(ctx, LauschigDatabase::class.java).allowMainThreadQueries().build()
        downloadSettings = DownloadSettings(ctx)
        db.itemDao().upsertDetails(ItemEntity(s, "b1", "l", "Heard book"))
        db.progressDao().replaceFromServer(ProgressEntity(s, "b1", 1.0, 1.0, true, 1))
        db.downloadDao().upsert(DownloadEntity(s, "b1", DownloadState.COMPLETE, 0))
    }

    @After fun tearDown(): Unit = runBlocking {
        downloadSettings.setAutoDeleteFinished(s, AutoDeleteMode.NEVER)
        db.close()
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, name: String, p: WorkerParameters) =
                CleanupReminderWorker(c, p, accounts, db.itemDao(), db.audioFileDao(), AppSettings(c), downloadSettings)
        }
        TestListenableWorkerBuilder<CleanupReminderWorker>(ctx).setWorkerFactory(factory).build().doWork()
    }

    private fun posted() = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Test fun remindsAsBefore_withoutAutomaticDeletion() {
        run()
        assertEquals(1, posted())
    }

    @Test fun staysSilent_whileHeardDownloadsAreDeletedAutomatically() = runBlocking {
        downloadSettings.setAutoDeleteFinished(s, AutoDeleteMode.AFTER_7_DAYS)
        run()
        assertEquals(0, posted())
    }
}
