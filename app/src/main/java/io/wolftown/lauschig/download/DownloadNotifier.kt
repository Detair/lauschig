package io.wolftown.lauschig.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.R
import javax.inject.Inject

/** Builds the foreground-service notification (`dataSync`) with download progress. */
class DownloadNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    /** [throttled]: the download yields to a playing stream (see [StreamPriority]). */
    fun info(itemId: String, title: String, done: Long, total: Long, throttled: Boolean = false): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val n: Notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(context.getString(if (throttled) R.string.download_notification_throttled else R.string.download_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) setProgress(1000, (done * 1000 / total).toInt().coerceIn(0, 1000), false)
                else setProgress(0, 0, true)
            }
            .build()
        return ForegroundInfo(((itemId.hashCode() and 0x7fffffff) % 1_000_000) + 10_000, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val CHANNEL = "downloads"
    }
}
