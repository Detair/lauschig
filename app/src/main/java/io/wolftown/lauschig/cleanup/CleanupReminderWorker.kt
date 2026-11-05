package io.wolftown.lauschig.cleanup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.download.AutoDeleteMode
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Once a week: if finished titles are still downloaded (and not ignored), posts one notification that opens "Clean up".
 * Silent while the automatic clean-up is on ([DownloadSettings.autoDeleteFinished]): it deletes them anyway.
 */
@HiltWorker
class CleanupReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accounts: AccountStore,
    private val items: ItemDao,
    private val audioFiles: AudioFileDao,
    private val settings: AppSettings,
    private val downloadSettings: DownloadSettings,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settings.cleanupReminder.first()) return Result.success()
        if (downloadSettings.autoDeleteFinished.first() != AutoDeleteMode.NEVER) return Result.success()
        val serverId = accounts.account.first()?.serverId ?: return Result.success()
        val ignored = settings.cleanupIgnored.first()
        val candidates = items.observeFinishedDownloaded(serverId).first().filter { "$serverId:${it.id}" !in ignored }
        if (candidates.isEmpty()) return Result.success()
        val bytesById = audioFiles.observeLocalBytes(serverId).first().associate { it.itemId to it.bytes }
        val bytes = candidates.sumOf { bytesById[it.id] ?: 0L }
        notify(candidates.size, bytes, candidates.take(3).map { it.title })
        return Result.success()
    }

    private fun notify(count: Int, bytes: Long, titles: List<String>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.cleanup_channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_CLEANUP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.resources.getQuantityString(R.plurals.cleanup_notification_title, count, count, Formatter.formatShortFileSize(context, bytes))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(titles.joinToString(", "))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL = "cleanup"
        /** Also cancelled on sign-out (it names the old account's titles). */
        const val NOTIFICATION_ID = 4711
        private const val WORK = "cleanup-reminder"

        /** Idempotent; called on every app start. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanupReminderWorker>(7, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
