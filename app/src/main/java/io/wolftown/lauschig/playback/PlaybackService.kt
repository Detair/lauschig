package io.wolftown.lauschig.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.sameOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Call
import javax.inject.Inject

/**
 * Thin glue: ExoPlayer + MediaLibrarySession (notification, lock screen, media buttons come from Media3 defaults).
 * All decisions live in [MediaItemFactory], [TimelineMapper], [ProgressTracker] and [PlayerControllerImpl].
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var api: ApiProvider
    @Inject lateinit var accountStore: AccountStore
    @Inject lateinit var itemDao: ItemDao
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var progressDao: ProgressDao
    @Inject lateinit var sessionDao: SessionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var bridge: PlaybackProgressBridge? = null
    private var connectionJob: Job? = null

    /** The last server seen online; kept while the state flips to offline (see [onCreate]). */
    @Volatile private var lastOnline: ConnectionState.Online? = null
    private val fallbackClient by lazy { api.unauthenticatedClient() } // cleartext-guarded base client

    override fun onCreate() {
        super.onCreate()
        // Requests to the last online server keep its authenticated client (token refresh included) even after the state
        // flipped to offline: a failed probe on a busy link must not turn the next stream request into a 401 that stops
        // playback. Other origins get the plain client (no credentials).
        val callFactory = Call.Factory { request ->
            val base = lastOnline?.baseUrl?.takeIf { request.url.sameOrigin(it) }
            (base?.let(api::clientFor) ?: fallbackClient).newCall(request)
        }
        connectionJob = scope.launch {
            // Another account (sign-out, new server) forgets the server, so its token never reaches an old queue's origin.
            accountStore.account.map { it?.let { a -> a.serverId to a.userId } }.distinctUntilChanged().collectLatest { who ->
                lastOnline = null
                if (who != null) api.connection.collect {
                    Log.i(TAG, "connection: $it")
                    if (it is ConnectionState.Online) lastOnline = it
                }
            }
        }

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
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { Log.w(TAG, "playback error ${error.errorCodeName}", error) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason") }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) { Log.i(TAG, "suppression=$reason") }
        })
        player = exo

        val tracker = ProgressTracker(progressDao, sessionDao, clock = System::currentTimeMillis, scope = scope)
        bridge = PlaybackProgressBridge(exo, tracker, { serverId, itemId -> loadPlayable(itemDao, episodeDao, serverId, itemId) }, scope)
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
        const val TAG = "PlaybackService"
    }
}
