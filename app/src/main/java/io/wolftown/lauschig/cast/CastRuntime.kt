package io.wolftown.lauschig.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.cast.CastPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.SessionState
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState as GmsCastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.wolftown.lauschig.R
import io.wolftown.lauschig.diagnostics.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the receiver does with its media, as far as the player needs to know. */
enum class ReceiverActivity {
    /** Nothing loaded yet, or on the way: the player's own state says what happens. */
    BUSY,

    /** The queue played to its end. */
    FINISHED,

    /** Loading or playing failed. */
    FAILED,

    /** It was stopped from elsewhere (another sender, the TV's remote, Google Home). */
    STOPPED,
}

/** A cast's events the player cannot read from [Player]: `RemoteCastPlayer` knows no end, no error and no load failure. */
interface ReceiverListener {
    /** New status of the receiver; [ReceiverLink.activity] has it. */
    fun onReceiverStatus()

    /** [detailedCode]: `MediaError.DetailedErrorCode`, null if the receiver named none. */
    fun onReceiverError(detailedCode: Int?)

    /** The Cast session is over for good (not just suspended). */
    fun onCastSessionEnded()

    /** A person moved the cast back to this phone in the system's output switcher (Android 13+). */
    fun onCastTakenBack()
}

/** The running Cast session as the player sees it. */
interface ReceiverLink {
    fun activity(): ReceiverActivity

    /** Ends the Cast session; the transfer back to this device follows from there. */
    fun endSession(stopReceiver: Boolean)

    fun setReceiverListener(listener: ReceiverListener?)
}

/**
 * Google's Cast framework behind one seam. **Started only when a person opens the full player** ([start]), never at the app's
 * start, by the car or by a play key: from then on Google's framework looks for devices on the network and logs its use
 * (see the privacy policy). Without Play Services nothing of it runs.
 */
interface CastRuntime : ReceiverLink {
    /** Google Play services are there and current. False: no Cast at all, the service plays as it always did. */
    val supported: Boolean

    /** The framework was started in this process: the service can put its Cast player on. */
    val started: StateFlow<Boolean>

    /** Main thread. Idempotent; does nothing if not [supported]. */
    fun start()

    /** While the full player is on screen: looks for devices (the framework alone does so only now and then). */
    fun setDiscovery(on: Boolean)

    /** `CastPlayer.Builder(context).setLocalPlayer(device)`; only after [started]. */
    @OptIn(UnstableApi::class)
    fun newCastPlayer(device: Player, transfer: CastPlayer.TransferCallback): Player
}

@OptIn(UnstableApi::class)
@Singleton
class GmsCastRuntime @Inject constructor(
    @ApplicationContext private val app: Context,
    private val state: CastState,
) : CastRuntime {
    override val supported: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
        } catch (e: RuntimeException) {
            false
        }
    }

    private val _started = MutableStateFlow(false)
    override val started: StateFlow<Boolean> = _started.asStateFlow()

    private var listener: ReceiverListener? = null
    private var client: RemoteMediaClient? = null
    private var selector: MediaRouteSelector? = null
    private var discovering = false
    private val handler = Handler(Looper.getMainLooper())

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            listener?.onReceiverStatus()
        }

        override fun onMediaError(mediaError: MediaError) {
            listener?.onReceiverError(mediaError.detailedErrorCode)
        }
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionSuspended(session: CastSession, reason: Int) = disconnected()
        override fun onSessionEnded(session: CastSession, error: Int) {
            disconnected()
            listener?.onCastSessionEnded()
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "the Cast session did not start: $error")
            if (error != CastStatusCodes.CANCELED) state.say(R.string.cast_connect_failed)
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            disconnected()
            listener?.onCastSessionEnded()
        }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    }

    private val routes = object : MediaRouter.Callback() {}

    private fun connected(session: CastSession) {
        state.update { it.copy(deviceName = session.castDevice?.friendlyName.orEmpty(), routeAvailable = true) }
        client?.unregisterCallback(mediaClientCallback)
        client = session.remoteMediaClient?.also { it.registerCallback(mediaClientCallback) }
    }

    private fun disconnected() {
        state.update { it.copy(deviceName = null) }
        client?.unregisterCallback(mediaClientCallback)
        client = null
    }

    override fun start() {
        if (_started.value || !supported) return
        try {
            val cast = Cast.getSingletonInstance(app)
            cast.addSessionManagerListener(sessions)
            // The Default Media Receiver, not Media3's default (its DRM variant): no registration, and it is the one documented for all Cast devices.
            cast.initialize(CastParams.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build())
            whenContextReady(cast, CONTEXT_POLLS)
            _started.value = true
        } catch (e: RuntimeException) {
            // A framework that cannot start: no button, and playback stays where it is.
            Log.w(TAG, "the Cast framework did not start", e)
        }
    }

    /**
     * Media3's [Cast] loads the framework in the background and does not say when it is done; another `getSharedInstance` with
     * options could create a second context, and one without options needs a provider in the manifest.
     */
    private fun whenContextReady(cast: Cast, polls: Int) {
        val context = CastContext.getSharedInstance()
        when {
            context != null -> contextReady(context)
            cast.castContextLoadFailure != null -> Log.w(TAG, "the Cast framework did not load", cast.castContextLoadFailure)
            polls > 0 -> handler.postDelayed({ whenContextReady(cast, polls - 1) }, CONTEXT_POLL_MS)
            else -> Log.w(TAG, "the Cast framework took too long to load")
        }
    }

    private fun contextReady(context: CastContext) {
        fun publish(castState: Int) = state.update { it.copy(routeAvailable = castState != GmsCastState.NO_DEVICES_AVAILABLE) }
        publish(context.castState)
        context.addCastStateListener(CastStateListener { publish(it) })
        context.addSessionTransferCallback(
            object : SessionTransferCallback() {
                override fun onTransferred(transferType: Int, sessionState: SessionState) {
                    if (transferType == TRANSFER_TYPE_FROM_REMOTE_TO_LOCAL) listener?.onCastTakenBack()
                }
            },
        )
        selector = context.mergedSelector
        applyDiscovery()
    }

    override fun setDiscovery(on: Boolean) {
        discovering = on
        applyDiscovery()
    }

    private fun applyDiscovery() {
        val s = selector ?: return
        val router = MediaRouter.getInstance(app)
        if (discovering) router.addCallback(s, routes, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY) else router.removeCallback(routes)
    }

    override fun newCastPlayer(device: Player, transfer: CastPlayer.TransferCallback): Player =
        CastPlayer.Builder(app).setLocalPlayer(device).setTransferCallback(transfer).build()

    override fun activity(): ReceiverActivity {
        val status = client?.mediaStatus ?: return ReceiverActivity.BUSY
        if (status.playerState != MediaStatus.PLAYER_STATE_IDLE) return ReceiverActivity.BUSY
        return when (status.idleReason) {
            MediaStatus.IDLE_REASON_FINISHED -> ReceiverActivity.FINISHED
            MediaStatus.IDLE_REASON_ERROR -> ReceiverActivity.FAILED
            MediaStatus.IDLE_REASON_CANCELED, MediaStatus.IDLE_REASON_INTERRUPTED -> ReceiverActivity.STOPPED
            else -> ReceiverActivity.BUSY
        }
    }

    override fun endSession(stopReceiver: Boolean) {
        if (_started.value) Cast.getSingletonInstance(app).endCurrentSession(stopReceiver)
    }

    override fun setReceiverListener(listener: ReceiverListener?) {
        this.listener = listener
    }

    private companion object {
        const val TAG = "CastRuntime"
        const val CONTEXT_POLL_MS = 200L
        const val CONTEXT_POLLS = 150
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds abstract fun castRuntime(impl: GmsCastRuntime): CastRuntime
}
