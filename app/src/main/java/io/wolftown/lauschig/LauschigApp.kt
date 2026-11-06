package io.wolftown.lauschig

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.wolftown.lauschig.account.AccountDataJanitor
import io.wolftown.lauschig.cleanup.CleanupReminderWorker
import io.wolftown.lauschig.cleanup.FinishedDownloadCleaner
import io.wolftown.lauschig.cleanup.FinishedQuestions
import io.wolftown.lauschig.cleanup.FinishedDownloadsWorker
import io.wolftown.lauschig.diagnostics.DiagnosticLog
import io.wolftown.lauschig.diagnostics.DiagnosticReport
import io.wolftown.lauschig.diagnostics.DiagnosticSecrets
import io.wolftown.lauschig.local.LocalFolders
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.playback.AutoResumer
import io.wolftown.lauschig.playback.NextVolumePrefetcher
import io.wolftown.lauschig.sync.SyncScheduler
import io.wolftown.lauschig.ui.common.deleteLegacyCoverCaches
import io.wolftown.lauschig.ui.common.newCoverImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.wolftown.lauschig.widget.WidgetAccountWatcher
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class LauschigApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var apiProvider: ApiProvider
    @Inject lateinit var prefetcher: NextVolumePrefetcher
    @Inject lateinit var autoResumer: AutoResumer
    @Inject lateinit var localFolders: LocalFolders
    @Inject lateinit var widgetAccounts: WidgetAccountWatcher
    @Inject lateinit var accountDataJanitor: AccountDataJanitor
    @Inject lateinit var finishedDownloads: FinishedDownloadCleaner
    @Inject lateinit var finishedQuestions: FinishedQuestions
    @Inject lateinit var downloadSettings: io.wolftown.lauschig.download.DownloadSettings
    @Inject lateinit var accountStore: io.wolftown.lauschig.account.AccountStore
    @Inject lateinit var diagnosticSecrets: DiagnosticSecrets
    @Inject lateinit var accountState: io.wolftown.lauschig.account.AccountStateCleaner
    @Inject lateinit var switchedDownloads: io.wolftown.lauschig.download.SwitchedDownloads

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** The only image loader: cover requests go through the cleartext-guarded client, without a token. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = newCoverImageLoader(this, apiProvider)

    override fun onCreate() {
        // Before anything else: a crash during start is in the diagnostic log too.
        DiagnosticLog.installCrashHandler()
        super.onCreate()
        // Off the main thread; nothing here awaits network I/O.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The user's own hosts and names are masked in the diagnostic log; its entries survive the process.
        diagnosticSecrets.start(scope)
        scope.launch(Dispatchers.IO) {
            DiagnosticLog.attach(File(noBackupFilesDir, DIAGNOSTIC_LOG))
            // The copy shared last time goes, and with it the read grant of the app it went to.
            DiagnosticReport.discardShared(this@LauschigApp)
        }
        scope.launch { syncScheduler.start(scope) }
        // Coil's former default cache directory is orphaned since the covers have their own; remove it (IO, never fails the start).
        scope.launch(Dispatchers.IO) { deleteLegacyCoverCaches(this@LauschigApp) }
        // Picks up files added to the device folders meanwhile (throttled; a WorkManager job, nothing waits for it).
        scope.launch { localFolders.scanIfDue() }
        // Sign-out or another server: the homescreen widget forgets the old account's title.
        scope.launch { widgetAccounts.run() }
        // A purge that failed during a sign-out is finished; downloads nobody can reach any more go.
        scope.launch {
            try {
                accountDataJanitor.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.wolftown.lauschig.diagnostics.Log.w("LauschigApp", "account data cleanup failed", e)
            }
        }
        // The player state is main-thread bound; the prefetcher only reads it.
        prefetcher.start(CoroutineScope(SupervisorJob() + Dispatchers.Main))
        // Registers its audio-device callback only while the setting is on.
        autoResumer.start(CoroutineScope(SupervisorJob() + Dispatchers.Main))
        CleanupReminderWorker.schedule(this)
        scope.launch {
            // The automatic clean-up became a setting per account: the old device-wide value is the signed-in account's.
            try {
                downloadSettings.migrateAutoDelete(accountStore.account.first()?.serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.wolftown.lauschig.diagnostics.Log.w("LauschigApp", "auto-delete setting not moved to the account", e)
            }
            // The same for the RMAB connection and the recent searches, one per device before profiles.
            try {
                accountState.migrate(accountStore.account.first()?.serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.wolftown.lauschig.diagnostics.Log.w("LauschigApp", "account state not moved to the account", e)
            }
            // A process that died in the middle of a profile switch: the downloads it paused go on.
            try {
                accountStore.account.first()?.serverId?.let { switchedDownloads.resume(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                io.wolftown.lauschig.diagnostics.Log.w("LauschigApp", "downloads paused by a switch did not go on", e)
            }
            // A "Download löschen?" question a killed process left open counts as "Behalten" – settled before the
            // clean-up looks at anything.
            finishedQuestions.settleLeftovers()
            // Finished downloads go by themselves if the user wants it; never a title the player holds.
            finishedDownloads.start(scope)
        }
        FinishedDownloadsWorker.schedule(this)
    }

    private companion object {
        /** App-private, never backed up; its entries are masked when recorded. */
        const val DIAGNOSTIC_LOG = "diagnostic-log.txt"
    }
}
