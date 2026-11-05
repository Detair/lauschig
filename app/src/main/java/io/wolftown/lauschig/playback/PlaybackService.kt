package io.wolftown.lauschig.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.wolftown.lauschig.R
import androidx.media3.common.AudioAttributes
import io.wolftown.lauschig.cleanup.TitleHandover
import io.wolftown.lauschig.diagnostics.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import io.wolftown.lauschig.MainActivity
import io.wolftown.lauschig.auto.CarAccess
import io.wolftown.lauschig.auto.CarButtons
import io.wolftown.lauschig.auto.CarContext
import io.wolftown.lauschig.auto.CarControls
import io.wolftown.lauschig.auto.CarLibrary
import io.wolftown.lauschig.auto.CarMediaItems
import io.wolftown.lauschig.auto.CarNode
import io.wolftown.lauschig.auto.CarSearch
import io.wolftown.lauschig.auto.CarStarts
import io.wolftown.lauschig.auto.ChapterTracker
import io.wolftown.lauschig.auto.CoverStore
import io.wolftown.lauschig.auto.CoverUris
import io.wolftown.lauschig.auto.LibraryCallback
import io.wolftown.lauschig.auto.MediaIds
import io.wolftown.lauschig.db.dao.CatalogDao
import io.wolftown.lauschig.db.dao.LibraryDao
import io.wolftown.lauschig.connection.CONNECTION_ANSWER_WAIT_MS
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.awaitConnectionAnswer
import io.wolftown.lauschig.connection.awaitOnlineAnswer
import io.wolftown.lauschig.connection.belongsTo
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.db.dao.EpisodeDao
import io.wolftown.lauschig.db.dao.QueueDao
import io.wolftown.lauschig.download.PlayDownloadStarter
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.queue.QueueAdvancer
import io.wolftown.lauschig.queue.queueEntryState
import io.wolftown.lauschig.queue.queueOwner
import io.wolftown.lauschig.sync.LibrarySync
import io.wolftown.lauschig.sync.SyncTrigger
import io.wolftown.lauschig.widget.WidgetPublisher
import io.wolftown.lauschig.widget.WidgetSource
import io.wolftown.lauschig.widget.WidgetStore
import kotlinx.coroutines.CancellationException
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.dao.SessionDao
import io.wolftown.lauschig.db.dao.TitleAudioDao
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.sameOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import javax.inject.Inject

/**
 * Thin glue: ExoPlayer + MediaLibrarySession (notification, lock screen, media buttons come from Media3 defaults).
 * All decisions live in [MediaItemFactory], [TimelineMapper], [ProgressTracker] and [PlayerControllerImpl].
 * Everything plays through a [PlaybackOutput]: the session, progress, queue, chapters, widget and car buttons use its
 * player, only the device's own parts (effects, skip silence, stream monitor) its ExoPlayer – the seam for Cast.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var api: ApiProvider
    @Inject lateinit var accountStore: AccountStore
    @Inject lateinit var itemDao: ItemDao
    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var progressDao: ProgressDao
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var streamMonitor: StreamMonitor
    @Inject lateinit var titleAudioDao: TitleAudioDao
    @Inject lateinit var playbackSettings: PlaybackSettings
    @Inject lateinit var lastPlayed: LastPlayedStore
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var queueDao: QueueDao
    @Inject lateinit var downloadDao: DownloadDao
    @Inject lateinit var mediaItemFactory: MediaItemFactory
    @Inject lateinit var librarySync: LibrarySync
    @Inject lateinit var playDownloadStarter: PlayDownloadStarter
    @Inject lateinit var syncTrigger: SyncTrigger
    @Inject lateinit var carLibrary: CarLibrary
    @Inject lateinit var carSearch: CarSearch
    @Inject lateinit var lastTitleStarter: LastTitleStarter
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var catalogDao: CatalogDao
    @Inject lateinit var libraryDao: LibraryDao
    @Inject lateinit var coverStore: CoverStore
    @Inject lateinit var widgetStore: WidgetStore
    @Inject lateinit var widgetSource: WidgetSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Library requests of cars and browsers; never the main thread (see [LibraryCallback]). */
    private val carScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: MediaLibrarySession? = null
    private var output: PlaybackOutput? = null
    /** What plays ([PlaybackOutput.player]); the session and everything that follows the playback use it. */
    private val player: Player? get() = output?.player
    private var bridge: PlaybackProgressBridge? = null
    private var connectionJob: Job? = null
    private var monitorJob: Job? = null
    private var comfort: PlaybackComfort? = null
    private var chapters: ChapterTracker? = null
    private var carPlayer: CarSafePlayer? = null
    private var carButtons: CarButtons? = null
    private var buttonState: CarButtons.State? = null
    private var widgets: WidgetPublisher? = null
    private var startGuard: ForegroundStartGuard? = null
    /** Counts covers that arrived in the background; browsers reload their lists when it changes. */
    private val coverArrivals = MutableStateFlow(0)
    /** Folders browsers subscribed to (Android Auto keeps a few); notified when the library changes. */
    private val subscribed = LinkedHashSet<String>()

    /** The last server seen online; kept while the state flips to offline (see [onCreate]). */
    @Volatile private var lastOnline: ConnectionState.Online? = null
    /** The account [lastOnline] was seen for: streams go through its client, which sends nothing once it is left. */
    @Volatile private var lastOnlineAccount: String? = null
    private val fallbackClient by lazy { api.unauthenticatedClient() } // cleartext-guarded base client

    @OptIn(UnstableApi::class) // transfer listener, load control
    override fun onCreate() {
        super.onCreate()
        running = true
        // Requests to the last online server keep its authenticated client (token refresh included) even after the state
        // flipped to offline: a failed probe on a busy link must not turn the next stream request into a 401 that stops
        // playback. Other origins get the plain client (no credentials).
        val callFactory = Call.Factory { request ->
            val base = lastOnline?.baseUrl?.takeIf { request.url.sameOrigin(it) }
            (base?.let { api.clientFor(lastOnlineAccount, it) } ?: fallbackClient).newCall(request)
        }
        connectionJob = scope.launch {
            // Another account (sign-out, new server) forgets the server, so its token never reaches an old queue's origin.
            // Only a state of this account's addresses counts: right after a switch the shared state may still replay the
            // previous server's Online before the reset arrives.
            accountStore.account.map { it?.let { a -> Triple(a.serverId, a.userId, a.addresses) } }.distinctUntilChanged().collectLatest { who ->
                lastOnline = null
                if (who != null) api.connection.collect {
                    Log.i(TAG, "connection: $it")
                    if (it is ConnectionState.Online && it.belongsTo(who.third)) {
                        lastOnlineAccount = who.first
                        lastOnline = it
                    }
                }
            }
        }

        val http = OkHttpDataSource.Factory(callFactory).setTransferListener(streamMonitor)
        // Audio is small: a long read-ahead bridges dead spots and gives a throttled download room (see StreamPriority).
        // The byte cap wins over the time window, so high-bitrate files get a shorter read-ahead instead of eating memory.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .build()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Only the defaults: the session player ([CarSafePlayer]) does every seek step, with the settings' lengths.
            .setSeekBackIncrementMs(PlaybackSettings.DEFAULT_SEEK_BACK * 1000L)
            .setSeekForwardIncrementMs(PlaybackSettings.DEFAULT_SEEK_FORWARD * 1000L)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { Log.w(TAG, "playback error ${error.errorCodeName}", error) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                Log.i(TAG, "playWhenReady=$playWhenReady reason=$reason")
                if (playWhenReady) watchStream(exo)
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) { Log.i(TAG, "suppression=$reason") }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) streamMonitor.onSeekOrPrepare()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) streamMonitor.onSeekOrPrepare()
            }
        })
        // Today on this device only; a Cast output (Phase 7) would put its player on top of [exo] here, and what is
        // built on `p` below follows it. The device's own parts (stream monitor above, volume and effects) stay on [exo].
        val out: PlaybackOutput = DeviceOutput(exo).also { output = it }
        val p = out.player
        // The only writer of the player's volume: the sleep fade (through the session player) times the compressor's switch.
        val volume = PlayerVolume { out.device.volume = it }
        comfort = PlaybackComfort(out.device, titleAudioDao, playbackSettings, scope, appScope, volume = volume, onTitleActivity = { serverId, itemId ->
            val at = System.currentTimeMillis()
            appScope.launch { lastPlayed.set(LastPlayed(serverId, itemId, at)) }
        }).also { it.start() }

        val tracker = ProgressTracker(progressDao, sessionDao, clock = System::currentTimeMillis, scope = scope)
        bridge = PlaybackProgressBridge(p, tracker, { serverId, itemId -> loadPlayable(itemDao, episodeDao, serverId, itemId) }, scope)
            .also { p.addListener(it) }
        // After the bridge, so the ended title is saved as finished before the next one replaces the queue.
        p.addListener(queueAdvanceListener(p))
        // The position reaches the server soon after a pause or the end of a title, not only with the periodic sync.
        p.addListener(ProgressSyncOnPause({ currentKey(p) }, syncTrigger::syncProgress))

        // Tapping the notification (or SystemUI's media controls) opens the player, like the widget, on top of whatever
        // start screen the user chose. Its own request code: the cleanup notification's MainActivity intent differs only
        // in its extras, and with FLAG_UPDATE_CURRENT the two would overwrite each other's target.
        val activity = PendingIntent.getActivity(
            this, SESSION_ACTIVITY_REQUEST, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PLAYER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The session player: "previous"/"next" from a steering wheel or headset move by the seek steps of the settings,
        // never to the book start.
        val safe = CarSafePlayer(p, PlaybackSettings.DEFAULT_SEEK_BACK * 1000L, PlaybackSettings.DEFAULT_SEEK_FORWARD * 1000L, volume)
            .also { carPlayer = it }
        val buttons = CarButtons(resources).also { carButtons = it }
        val chapterTracker = ChapterTracker(p, { serverId, key -> loadPlayable(itemDao, episodeDao, serverId, key) }, scope, onTitle = { updateButtons() })
        chapters = chapterTracker
        val built = MediaLibrarySession.Builder(this, safe, libraryCallback(buttons))
            .setSessionActivity(activity)
            .setMediaButtonPreferences(buttons.buttons(currentButtonState()))
            .build()
        session = built
        chapterTracker.start()
        p.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = updateButtons()

            // A title the service started by itself (car, voice, resumption, queue) shows up in the app too; the app's
            // own starts are left alone there.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // What the player holds is never deleted by the automatic clean-up, also before the app adopts it.
                TitleHandover.serviceHolds(currentKey(exo)?.second)
                playerController.adoptFromService()
            }

            // Once something plays, Media3 owns the foreground: a pending play-key guard lets go for good.
            override fun onEvents(player: Player, events: Player.Events) {
                if (isEngaged(p)) startGuard?.onEngaged()
            }
        })
        scope.launch { sleepTimer.state.map { it?.mode }.distinctUntilChanged().collect { updateButtons() } }
        scope.launch {
            combine(playbackSettings.seekBackSec, playbackSettings.seekForwardSec, ::Pair).collect { (back, forward) ->
                safe.setSteps(back * 1000L, forward * 1000L)
                updateButtons()
            }
        }
        watchLibrary(built)
        startGuard = ForegroundStartGuard(scope, StartHost(p))
        // The homescreen widget follows the player (also right now: a fresh process starts with an empty player).
        widgets = WidgetPublisher({ WidgetPublisher.snapshot(p) }, { p.mediaItemCount > 0 }, scope, appScope, widgetStore, widgetSource::refresh)
            .also { p.addListener(it); it.start() }
        grantCoverReaders()
        coverStore.onFetched = { serverId, itemId, delivered -> scope.launch { coverArrived(serverId, itemId, delivered) } }
        // Android 12+ refuses a foreground start from the background (e.g. auto-resume while the app is not visible).
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, "foreground start from the background was refused")
            }
        })
    }

    /**
     * SystemUI and Bluetooth read the artwork URI of the playing title from the platform session without connecting to
     * us, so they never pass [LibraryCallback]'s grant: grant them (and the car hosts) the cover prefix up front. Packages
     * that are not installed are skipped.
     */
    private fun grantCoverReaders() {
        val prefix = CoverUris.prefix(CoverUris.authority(packageName))
        appScope.launch {
            CarAccess.COVER_READERS.forEach { pkg ->
                try {
                    grantUriPermission(pkg, prefix, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                } catch (e: Exception) {
                    // not installed
                }
            }
        }
    }

    /**
     * A cover the car or the notification asked for arrived in the background. If no waiting request ([delivered], the
     * car's pipes) got it, browsers reload their lists (debounced, see [watchLibrary]; the reload throws the car back to
     * the top of a grid, so only then) and find it under a `ready` URI. For the playing title the artwork URIs get a fresh
     * query, because Media3, AA and SystemUI remember a failed load per URI. Same media URIs, so ExoPlayer updates the
     * items in place.
     */
    private fun coverArrived(serverId: String, itemId: String, delivered: Boolean) {
        if (!delivered) coverArrivals.value++
        appScope.launch { widgetSource.coverArrived(serverId, itemId) }
        val p = player ?: return
        val (sid, key) = currentKey(p) ?: return
        if (sid != serverId || PlayableKey.parse(key).libraryItemId != itemId) return
        val stamp = System.currentTimeMillis().toString()
        val items = (0 until p.mediaItemCount).map { i ->
            val item = p.getMediaItemAt(i)
            val art = item.mediaMetadata.artworkUri ?: return@map item
            val fresh = art.buildUpon().clearQuery().appendQueryParameter("ready", stamp).build()
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(fresh).build()).build()
        }
        if (items.isNotEmpty()) p.replaceMediaItems(0, items.size, items)
    }

    private fun currentButtonState() = CarButtons.State(
        speed = Math.round((player?.playbackParameters?.speed ?: 1f) * 100) / 100f,
        sleep = sleepTimer.state.value?.mode,
        hasChapters = chapters?.hasChapters == true,
        seekBackSec = ((carPlayer?.seekBackIncrement ?: 0L) / 1000).toInt().takeIf { it > 0 } ?: PlaybackSettings.DEFAULT_SEEK_BACK,
        seekForwardSec = ((carPlayer?.seekForwardIncrement ?: 0L) / 1000).toInt().takeIf { it > 0 } ?: PlaybackSettings.DEFAULT_SEEK_FORWARD,
    )

    /** New media buttons only when what they show changed (speed, sleep timer, chapters, seek steps). */
    @OptIn(UnstableApi::class) // media button preferences
    private fun updateButtons() {
        val s = session ?: return
        val buttons = carButtons ?: return
        val state = currentButtonState()
        if (state == buttonState) return
        buttonState = state
        s.setMediaButtonPreferences(buttons.buttons(state))
    }

    /** The car buttons; false if there was nothing to do (no title, no chapters). */
    private fun onCarCommand(action: String): Boolean {
        val p = player ?: return false
        if (p.mediaItemCount == 0) return false
        return when (action) {
            CarButtons.SEEK_BACK -> { carPlayer?.step(forward = false); true }
            CarButtons.SEEK_FORWARD -> { carPlayer?.step(forward = true); true }
            CarButtons.SPEED -> { p.setPlaybackSpeed(CarControls.nextSpeed(p.playbackParameters.speed)); true }
            CarButtons.SLEEP -> {
                val next = CarControls.nextSleep(sleepTimer.state.value?.mode)
                if (next == null) sleepTimer.cancel() else sleepTimer.start(next)
                updateButtons()
                true
            }
            CarButtons.CHAPTER_BACK -> chapters?.seekChapter(back = true) == true
            CarButtons.CHAPTER_NEXT -> chapters?.seekChapter(back = false) == true
            else -> false
        }
    }

    /**
     * The server for streams of a car, voice or resumption start: the one online now; during the first connection check
     * of a cold start its answer (at most [CONNECTION_ANSWER_WAIT_MS]); none when the check answered "offline". Runs in
     * the library callback's background scope, never on the main thread.
     */
    private suspend fun streamBase(): HttpUrl? =
        (api.connectionState.value as? ConnectionState.Online)?.baseUrl
            ?: awaitOnlineAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS)?.baseUrl

    private suspend fun carContext() = CarContext(accountStore.account.first()?.serverId, api.connectionState.value is ConnectionState.Online)

    /** The title in the player if it can just play on (not ended, not failed). Main thread. */
    private fun loadedTitle(): CarNode.Title? {
        val p = player ?: return null
        val state = p.playbackState
        if (state != Player.STATE_READY && state != Player.STATE_BUFFERING) return null
        return currentKey(p)?.let { (serverId, key) -> CarNode.Title(serverId, key) }
    }

    private suspend fun lastTitle(): CarNode.Title? = lastTitleStarter.last()?.let { CarNode.Title(it.serverId, it.itemId) }

    private fun libraryCallback(buttons: CarButtons): LibraryCallback {
        val starter = ServiceTitleStarter(
            loadItem = { serverId, key -> loadPlayable(itemDao, episodeDao, serverId, key) },
            factory = mediaItemFactory,
            baseUrl = ::streamBase,
            loadDetails = librarySync::loadDetailsForPlayable,
            io = Dispatchers.IO,
            setQueue = { _, _, _ -> error("car starts go through the session") },
            rewindAfterPause = { pause -> rewindMs(pause, playbackSettings.rewindOnResumeSec.first()) },
        )
        val starts = CarStarts(
            resolveTitle = starter::resolve,
            // After the first connection check: right after a cold start the state is still Offline.
            searchTitle = { query, hints ->
                val online = awaitConnectionAnswer(api.connection, api.checking, CONNECTION_ANSWER_WAIT_MS)
                val ctx = CarContext(accountStore.account.first()?.serverId, online)
                carSearch.resolve(query, hints, ctx, ::lastTitle)
            },
            lastTitle = ::lastTitle,
            accountServer = { accountStore.account.first()?.serverId },
            current = { withContext(Dispatchers.Main.immediate) { loadedTitle() } },
        )
        return LibraryCallback(
            context = this,
            scope = carScope,
            library = carLibrary,
            search = carSearch,
            starts = starts,
            mediaItems = CarMediaItems(CoverUris.authority(packageName), coverStore::present),
            carContext = ::carContext,
            lastTitle = ::lastTitle,
            buttons = { buttons.buttons(currentButtonState()) },
            onCommand = ::onCarCommand,
            onStart = { out -> if (out.streaming) playDownloadStarter.start(out.title.serverId, out.title.key) },
            onPlayLoaded = { scope.launch { player?.play() } },
            onSubscribed = { id -> subscribed.remove(id); subscribed += id; while (subscribed.size > MAX_SUBSCRIBED) subscribed.remove(subscribed.first()) },
            onResumption = { ok, message ->
                scope.launch {
                    // A play key that cannot start anything says why (Bluetooth, car, widget); the guard then lets go.
                    if (!ok && startGuard?.armed == true && message != null) Toast.makeText(this@PlaybackService, message, Toast.LENGTH_LONG).show()
                    startGuard?.resumption(ok)
                }
            },
        )
    }

    /** A play key (Bluetooth, car, widget) may have started us with startForegroundService; see [ForegroundStartGuard]. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = player
        if (intent != null && p != null && ForegroundStartGuard.shouldArm(intent, engaged = isEngaged(p))) startGuard?.arm()
        return super.onStartCommand(intent, flags, startId)
    }

    /** Something plays or buffers to play: Media3 shows the notification and goes foreground itself. */
    private fun isEngaged(p: Player): Boolean =
        p.mediaItemCount > 0 && p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)

    private fun foregroundType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0

    /** What the play-key guard ([ForegroundStartGuard]) does to this service. Main thread. */
    private inner class StartHost(private val p: Player) : ForegroundStartGuard.Host {
        override fun engaged() = isEngaged(p)

        override fun hasItems() = p.mediaItemCount > 0

        /**
         * Same id, same notification: Android neither swaps nor cancels anything. Media3's next notification update for a
         * player that does not play calls `stopForeground(DETACH)` itself, as it always does.
         */
        override fun adoptMediaNotification(): Boolean {
            val nm = getSystemService(NotificationManager::class.java) ?: return false
            val posted = try {
                nm.activeNotifications.firstOrNull { it.id == ForegroundStartGuard.MEDIA_NOTIFICATION_ID && it.tag == null }?.notification
            } catch (e: Exception) {
                null
            } ?: return false
            return try {
                ServiceCompat.startForeground(this@PlaybackService, ForegroundStartGuard.MEDIA_NOTIFICATION_ID, posted, foregroundType())
                Log.i(TAG, "play key played nothing: in the foreground with the media notification")
                true
            } catch (e: Exception) {
                Log.w(TAG, "could not go foreground with the media notification", e)
                false
            }
        }

        override fun showPlaceholder() {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(START_CHANNEL, getString(R.string.playback_start_channel), NotificationManager.IMPORTANCE_LOW))
                val notification = NotificationCompat.Builder(this@PlaybackService, START_CHANNEL)
                    .setSmallIcon(R.drawable.ic_widget_play)
                    .setContentTitle(getString(R.string.app_name))
                    .setSilent(true)
                    .build()
                ServiceCompat.startForeground(this@PlaybackService, START_NOTIFICATION_ID, notification, foregroundType())
                Log.i(TAG, "play key: in the foreground with the placeholder")
            } catch (e: Exception) {
                Log.w(TAG, "could not go foreground with the placeholder", e)
            }
        }

        /**
         * Out of the foreground and the placeholder gone (also if Media3's own `stopForeground(DETACH)` detached it
         * meanwhile); an empty service stops – a loaded title stays with Media3. If a controller (the app, a car) is still
         * bound, the service stays alive without being in the foreground.
         */
        override fun leavePlaceholder() {
            try {
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java)?.cancel(START_NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(TAG, "could not leave the foreground start", e)
            }
            Log.i(TAG, "play key started nothing: left the foreground start")
            if (p.mediaItemCount == 0) stopSelf()
        }
    }

    /**
     * Browsers (Android Auto) cache what they showed: when the connection comes or goes, or the catalog, the downloads,
     * the device titles or the set of titles in progress change, the subscribed folders are told to reload.
     */
    @OptIn(FlowPreview::class)
    private fun watchLibrary(session: MediaLibrarySession) {
        scope.launch {
            accountStore.account.map { it?.serverId }.distinctUntilChanged().collectLatest { sid ->
                val dev = DeviceLibrary.SERVER_ID
                val parts: List<Flow<Any>> = listOf(
                    api.connection.map { it is ConnectionState.Online },
                    coverArrivals,
                    catalogDao.observeVersion(dev),
                    itemDao.observeInProgress(dev).map { list -> list.map { it.id } },
                ) + if (sid == null) listOf(flowOf(Unit)) else listOf(
                    libraryDao.observeAll(sid).map { list -> list.map { it.id to it.name } },
                    catalogDao.observeVersion(sid),
                    downloadDao.observeCompleteIds(sid),
                    itemDao.observeInProgress(sid).map { list -> list.map { it.id } },
                )
                combine(parts) { it.toList() }.distinctUntilChanged().debounce(LIBRARY_DEBOUNCE_MS).collect {
                    val ids = LinkedHashSet<String>()
                    ids += MediaIds.of(CarNode.Root)
                    listOf(CarNode.Continue, CarNode.Downloads, CarNode.SeriesList, CarNode.Libraries).forEach { ids += MediaIds.of(it) }
                    ids += subscribed
                    ids.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
                }
            }
        }
    }

    /**
     * The next queued title starts right here when one ends, on the service's own player: the service keeps running
     * and never depends on the app's MediaController. Media3 1.11.1 keeps the service in the foreground for
     * `DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS` (10 min) after the player stops being "engaged" (ENDED is not), so the
     * few database reads in between do not drop the foreground (see the phase-3B report).
     */
    private fun queueAdvanceListener(p: Player): Player.Listener {
        val starter = ServiceTitleStarter(
            loadItem = { serverId, key -> loadPlayable(itemDao, episodeDao, serverId, key) },
            factory = mediaItemFactory,
            baseUrl = { lastOnline?.baseUrl },
            loadDetails = librarySync::loadDetailsForPlayable,
            io = Dispatchers.IO,
            setQueue = { items, index, offsetMs ->
                p.setMediaItems(items, index, offsetMs)
                p.prepare()
                p.play()
            },
            onStreamStarted = playDownloadStarter::start,
        )
        val advancer = QueueAdvancer(
            entries = { serverId -> queueDao.entries(serverId).map { it.itemKey } },
            remove = queueDao::remove,
            check = { serverId, key -> queueEntryState(itemDao, episodeDao, downloadDao, api.connectionState.value is ConnectionState.Online, serverId, key) },
            sleepEndOfChapter = { sleepTimer.state.value?.mode == SleepMode.EndOfChapter },
            // The queue lives in the account's namespace with mixed keys; a device key starts the device title.
            start = { owner, key -> starter.start(DeviceLibrary.serverFor(owner, key) ?: owner, key) },
            // Checked again right before a start: a sign-out may have come in between.
            stillOwner = { owner -> accountStore.account.first()?.serverId == owner },
            pick = { _, key -> TitleHandover.pick(key) },
        )
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val extras = p.currentMediaItem?.mediaMetadata?.extras ?: return
                val serverId = extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return
                val key = extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return
                scope.launch {
                    try {
                        // A device title ended: the queue it may belong to is the signed-in account's. Never another server's.
                        val owner = queueOwner(serverId, accountStore.account.first()?.serverId) ?: return@launch
                        advancer.onEnded(owner, key)?.let { Log.i(TAG, "queue: next title $it") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "queue advance failed", e)
                    }
                }
            }
        }
    }

    private fun currentKey(p: Player): Pair<String, String>? {
        val extras = p.currentMediaItem?.mediaMetadata?.extras ?: return null
        return (extras.getString(MediaItemFactory.EXTRA_SERVER_ID) ?: return null) to (extras.getString(MediaItemFactory.EXTRA_ITEM_ID) ?: return null)
    }

    /** Polls the player for [StreamMonitor] while it wants to play; reports the stream inactive once, then stops. */
    private fun watchStream(exo: ExoPlayer) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (true) {
                val wants = exo.playWhenReady
                val state = exo.playbackState
                streamMonitor.update(
                    // Ended or idle (error, stopped): nothing loads any more, downloads must not wait for it.
                    active = wants && isStream(exo.currentMediaItem) && state != Player.STATE_ENDED && state != Player.STATE_IDLE,
                    playing = exo.isPlaying,
                    buffering = state == Player.STATE_BUFFERING,
                    bufferedAheadMs = exo.totalBufferedDuration,
                    bufferComplete = bufferComplete(exo),
                )
                if (!wants) break
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    /** The current file is buffered to its end and no streamed file follows: the read-ahead cannot grow. */
    private fun bufferComplete(exo: ExoPlayer): Boolean {
        val duration = exo.duration
        if (duration == C.TIME_UNSET || exo.bufferedPosition < duration) return false
        val next = exo.nextMediaItemIndex
        return next == C.INDEX_UNSET || !isStream(exo.getMediaItemAt(next))
    }

    private fun isStream(item: MediaItem?): Boolean = item?.localConfiguration?.uri?.scheme.let { it == "http" || it == "https" }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background; stop only if nothing is playing (and never while the play-key guard waits).
        val p = player
        if (stopsOnTaskRemoved(p != null, p?.playWhenReady == true, p?.mediaItemCount ?: 0, startGuard?.armed == true)) stopSelf()
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        monitorJob?.cancel()
        streamMonitor.update(active = false, playing = false, buffering = false, bufferedAheadMs = 0)
        // Capture the position and detach the listener before release; the final save outlives the service scope.
        val final = bridge?.currentGlobalSec()
        bridge?.let { player?.removeListener(it) }
        comfort?.release()
        comfort = null
        chapters?.release()
        chapters = null
        coverStore.onFetched = null
        sleepTimer.cancel() // nothing left to put to sleep
        player?.pause()
        widgets?.let { player?.removeListener(it); it.release() }
        widgets = null
        startGuard?.release()
        startGuard = null
        bridge?.shutdown(final, appScope)
        session?.release()
        output?.release()
        session = null
        output = null
        scope.cancel()
        carScope.cancel()
        running = false
        super.onDestroy()
    }

    companion object {
        /**
         * Swiped from Recents: stop unless something plays. Never while the play-key guard is armed: a service started
         * with `startForegroundService` that stops before `startForeground` crashes; the guard settles and stops an empty
         * service itself.
         */
        fun stopsOnTaskRemoved(hasPlayer: Boolean, playWhenReady: Boolean, itemCount: Int, guardArmed: Boolean): Boolean =
            !guardArmed && (!hasPlayer || !playWhenReady || itemCount == 0)

        /** The service runs in this process (the widget shows "playing" only then). */
        @Volatile
        var running: Boolean = false
            private set

        /** Request code of the session activity (the cleanup notification uses 0). */
        private const val SESSION_ACTIVITY_REQUEST = 1
        private const val MIN_BUFFER_MS = 5 * 60_000
        private const val MAX_BUFFER_MS = 10 * 60_000
        private const val START_BUFFER_MS = 2_500
        private const val REBUFFER_MS = 5_000
        private const val MONITOR_INTERVAL_MS = 500L
        private const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024
        private const val LIBRARY_DEBOUNCE_MS = 1_500L
        private const val MAX_SUBSCRIBED = 32
        private const val TAG = "PlaybackService"
        private const val START_CHANNEL = "playback_start"
        /** Not Media3's notification id (1001) nor its shutdown id (20938). */
        private const val START_NOTIFICATION_ID = 4711
    }
}
