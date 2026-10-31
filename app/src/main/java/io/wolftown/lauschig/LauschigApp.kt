package io.wolftown.lauschig

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.sync.SyncScheduler
import io.wolftown.lauschig.ui.common.newCoverImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LauschigApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var apiProvider: ApiProvider

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** The only image loader: cover requests go through the authenticated, cleartext-guarded client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = newCoverImageLoader(this, apiProvider)

    override fun onCreate() {
        super.onCreate()
        // Off the main thread; nothing here awaits network I/O.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch { syncScheduler.start(scope) }
    }
}
