package io.wolftown.lauschig.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.net.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Thin glue: ExoPlayer + MediaLibrarySession (notification, lock screen, media buttons come from Media3 defaults).
 * All decisions live in [MediaItemFactory], [TimelineMapper], [ProgressTracker] and [PlayerControllerImpl].
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var api: ApiProvider
    @Inject lateinit var itemDao: ItemDao
    @Inject lateinit var progressDao: ProgressDao
    @Inject lateinit var sessionDao: SessionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var bridge: PlaybackProgressBridge? = null
    private var connectionJob: Job? = null

    @Volatile private var online: ConnectionState.Online? = null
    private val fallbackClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        // Authenticated client of the current server (token refresh included); plain client while offline (fails fast).
        val callFactory = Call.Factory { request -> (online?.let { api.clientFor(it.baseUrl) } ?: fallbackClient).newCall(request) }
        connectionJob = scope.launch { api.connection.collect { online = it as? ConnectionState.Online } }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, OkHttpDataSource.Factory(callFactory))),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        player = exo

        val tracker = ProgressTracker(progressDao, sessionDao, clock = System::currentTimeMillis, scope = scope)
        bridge = PlaybackProgressBridge(exo, tracker, { serverId, itemId -> itemDao.observeItem(serverId, itemId).first() }, scope)
            .also { exo.addListener(it) }

        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, exo, object : MediaLibrarySession.Callback {})
            .setSessionActivity(activity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background; stop only if nothing is playing.
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        // Capture the position and detach the listener before release; the final save outlives the service scope.
        val final = bridge?.currentGlobalSec()
        bridge?.let { player?.removeListener(it) }
        player?.pause()
        bridge?.shutdown(final, appScope)
        session?.release()
        player?.release()
        session = null
        player = null
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SEEK_MS = 30_000L
    }
}
