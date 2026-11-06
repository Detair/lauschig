package io.wolftown.lauschig.cast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.wolftown.lauschig.diagnostics.Log
import io.wolftown.lauschig.playback.DeviceOutput
import io.wolftown.lauschig.playback.PlaybackOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** With Play Services the Cast output; without them the ExoPlayer itself, exactly as before. */
fun playbackOutput(device: ExoPlayer, runtime: CastRuntime, sessions: CastSessionSource, state: CastState, scope: CoroutineScope): PlaybackOutput =
    if (runtime.supported) CastOutput(device, runtime, sessions, state, scope) else DeviceOutput(device)

/**
 * Playing on this device or on a Cast receiver: [player] is [CastOutputPlayer], which takes Media3's `CastPlayer` on top of
 * [device] once a person opened the full player ([CastRuntime.started]). Only on a device with Play Services.
 */
@OptIn(UnstableApi::class)
class CastOutput(
    override val device: ExoPlayer,
    private val runtime: CastRuntime,
    sessions: CastSessionSource,
    state: CastState,
    scope: CoroutineScope,
) : PlaybackOutput {
    private val output = CastOutputPlayer(device, runtime, sessions, scope, state::say, carConnected = { state.ui.value.carConnected })
    override val player: Player get() = output
    private val receiverVolume = ReceiverVolume({ output }, scope)

    /** A person's speed while the title plays on a receiver. */
    fun onSpeedRequested(block: (Float) -> Unit) {
        output.onSpeedRequested = block
    }

    /** The title reached a receiver or came back. */
    fun onOutputChanged(block: () -> Unit) {
        output.onOutputChanged = block
    }

    init {
        runtime.setReceiverListener(output)
        scope.launch {
            runtime.started.first { it }
            try {
                output.attach(runtime.newCastPlayer(device, output))
            } catch (e: RuntimeException) {
                // Never at the cost of playing: without it everything stays on this device.
                Log.w("CastOutput", "no Cast player", e)
            }
        }
    }

    override fun setVolume(volume: Float) {
        if (isLocal) device.volume = volume else receiverVolume.set(volume)
    }

    override fun release() {
        runtime.setReceiverListener(null)
        output.release()
    }
}

/**
 * The sleep timer's fade on a receiver. The stream's volume where the receiver offers it (it leaves the device's own volume
 * alone, 1 is "as before"), else the device's volume, scaled from where it stood and put back at 1. A message per step goes over
 * the network, so steps come at most every [minGapMs]; the last value always goes out.
 */
@OptIn(UnstableApi::class)
class ReceiverVolume(
    private val player: () -> Player,
    private val scope: CoroutineScope,
    private val minGapMs: Long = MIN_GAP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastSent = 0L
    private var pending: Float? = null
    private var job: Job? = null
    /** The device's volume before the fade touched it. */
    private var deviceBase: Int? = null

    fun set(volume: Float) {
        val now = clock()
        val wait = lastSent + minGapMs - now
        if (wait <= 0 || volume >= 1f) {
            job?.cancel()
            pending = null
            send(volume)
            return
        }
        pending = volume
        if (job?.isActive == true) return
        job = scope.launch {
            delay(wait)
            pending?.let { send(it) }
            pending = null
        }
    }

    private fun send(volume: Float) {
        lastSent = clock()
        val p = player()
        when {
            p.isCommandAvailable(Player.COMMAND_SET_VOLUME) -> p.volume = volume
            p.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS) -> {
                val base = deviceBase ?: p.deviceVolume.also { deviceBase = it }
                p.setDeviceVolume((base * volume).roundToInt(), C.VOLUME_FLAG_REMOVE_SOUND_AND_VIBRATE)
                if (volume >= 1f) deviceBase = null
            }
        }
    }

    private companion object {
        const val MIN_GAP_MS = 500L
    }
}
