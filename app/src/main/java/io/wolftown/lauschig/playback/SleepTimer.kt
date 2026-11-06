package io.wolftown.lauschig.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the player shows: the running timer, its listening time left and whether a shake extends it now. */
data class SleepTimerUi(val mode: SleepMode, val remainingMs: Long, val shakeToExtend: Boolean)

/** Reports shakes while started; only started while a sleep timer is in its last minute. */
interface ShakeSource {
    fun start(onShake: () -> Unit)
    fun stop()
}

/** Accelerometer at UI rate (no permission, no high sampling rate). */
class AccelerometerShakeSource @Inject constructor(@ApplicationContext context: Context) : ShakeSource {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    override fun start(onShake: () -> Unit) {
        if (listener != null) return
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val detector = ShakeDetector()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values
                if (detector.onSample(v[0], v[1], v[2], event.timestamp / 1_000_000)) onShake()
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        listener = l
        sensors.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun stop() {
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
    }
}

/**
 * App-wide sleep timer (survives leaving the player). Ticks while active, plays a soft hint tone 30 s before the end
 * (if the setting is on), fades the player volume over the last seconds, pauses at the end and listens for a shake
 * only during the last minute. Rules: [SleepTimerMachine].
 */
@Singleton
class SleepTimer(
    private val player: PlayerController,
    private val shakes: ShakeSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val tickMs: Long = 250,
    /** Called after a shake extended the timer (haptic feedback). */
    private val onExtended: () -> Unit = {},
    /** The hint tone 30 s before the end ([SleepEffect.playChime]). */
    private val chime: SleepChime = SleepChime {},
    /** The setting "hint tone before the end". */
    chimeEnabled: Flow<Boolean> = flowOf(true),
) {
    @Inject constructor(
        player: PlayerController,
        shakes: AccelerometerShakeSource,
        @ApplicationContext context: Context,
        chime: AudioTrackChime,
        settings: PlaybackSettings,
    ) : this(
        player, shakes, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), onExtended = { vibrate(context) },
        chime = chime, chimeEnabled = settings.sleepChime,
    )

    private val machine = SleepTimerMachine()
    private val _state = MutableStateFlow<SleepTimerUi?>(null)
    val state: StateFlow<SleepTimerUi?> = _state.asStateFlow()
    private var job: Job? = null
    private var volume = 1f
    private var listening = false
    /** The title the timer last ticked for; another one re-arms chapter mode. */
    private var itemId: String? = null
    private var chimeOn = true

    init {
        scope.launch { chimeEnabled.collect { chimeOn = it } }
    }

    fun start(mode: SleepMode) {
        itemId = player.state.value.itemId
        machine.start(mode, input())
        job?.cancel()
        job = scope.launch {
            var last = clock()
            while (machine.isActive) {
                val now = clock()
                step(now - last)
                last = now
                if (machine.isActive) delay(tickMs)
            }
        }
        publish()
    }

    fun cancel() {
        machine.cancel()
        job?.cancel(); job = null
        finish()
    }

    /** One timer step; public for tests that drive the clock themselves. */
    internal fun step(deltaMs: Long) {
        val s = player.state.value
        // While a title loads, the state is reset (no duration, no chapters): no tick may read it as "at the end".
        if (s.itemId == null || s.isLoading || s.durationSec <= 0) return
        if (s.itemId != itemId) {
            itemId = s.itemId
            machine.rearm(input())
        }
        // Near the end the poll of the controller is too coarse: read the player now.
        if ((machine.remainingMs(input()) ?: Long.MAX_VALUE) <= machine.fadeMs + 2 * tickMs) player.refreshNow()
        val effect = machine.tick(input(), deltaMs)
        // Not while casting: the sound is in another place, and this phone's media volume is out of reach of the keys (they steer the
        // receiver), so a tone could come at any loudness in a quiet room. The receiver's fade says the same.
        if (effect.playChime && chimeOn && s.outputName == null) chime.play()
        if (effect.pause) {
            player.pause()
            finish()
            return
        }
        if (!machine.isActive) { // turned itself off after a long pause
            finish()
            return
        }
        setVolume(effect.volume)
        publish()
    }

    private fun shaken() {
        if (machine.shake(input())) {
            onExtended()
            setVolume(machine.volumeFor(machine.remainingMs(input()) ?: Long.MAX_VALUE))
            publish()
        }
    }

    private fun finish() {
        setVolume(1f)
        stopShakes()
        _state.value = null
    }

    private fun publish() {
        val i = input()
        val mode = machine.mode ?: return finish()
        val shakeable = machine.inShakeWindow(i)
        if (shakeable && !listening) { listening = true; shakes.start { scope.launch { shaken() } } }
        if (!shakeable) stopShakes()
        _state.value = SleepTimerUi(mode, machine.remainingMs(i) ?: 0, shakeable)
    }

    private fun stopShakes() {
        if (listening) { listening = false; shakes.stop() }
    }

    private fun setVolume(v: Float) {
        if (v == volume) return
        volume = v
        player.setVolume(v)
    }

    private fun input(): SleepInput = player.state.value.let {
        SleepInput(it.isPlaying, it.positionSec, it.speed, it.chapterIndex, it.chapterEndsSec, it.durationSec)
    }

    private companion object {
        fun vibrate(context: Context) {
            try {
                val v = context.getSystemService(android.os.Vibrator::class.java) ?: return
                v.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: SecurityException) {
                // No VIBRATE permission declared: extending silently is fine.
            }
        }
    }
}
