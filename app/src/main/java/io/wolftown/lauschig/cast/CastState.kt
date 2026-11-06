package io.wolftown.lauschig.cast

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** What the line under the title says about a connected Cast device. */
enum class CastActivity {
    /** The device plays or loads the title: "Wird auf … abgespielt". */
    PLAYING,

    /** Connected, but it holds no title of ours (stopped from elsewhere, played to the end, nothing started yet). */
    CONNECTED,

    /** The connection is lost for now; the device may play on, and nothing here can reach it. */
    SUSPENDED,

    /** The session is ending, or nothing plays there: no line. */
    NONE,
}

/** What the screens know about Cast. Without Play Services it stays at its defaults: no button, nothing else changes. */
data class CastUi(
    /** A Cast device is known on the network (or one is connected). */
    val routeAvailable: Boolean = false,
    /** The device that plays, while a Cast session is on. */
    val deviceName: String? = null,
    /** A car is connected: playback stays on this device. */
    val carConnected: Boolean = false,
    /** What [deviceName] is doing. */
    val activity: CastActivity = CastActivity.PLAYING,
) {
    val showButton: Boolean get() = routeAvailable && !carConnected
}

/** The meeting point of the Cast framework, the service's player and the screens (they share no other object). */
@Singleton
class CastState @Inject constructor() {
    private val _ui = MutableStateFlow(CastUi())
    val ui: StateFlow<CastUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** What the player should say, once each: why a cast ended or did not start. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun update(change: (CastUi) -> CastUi) = _ui.update(change)

    fun say(@StringRes message: Int) {
        _messages.tryEmit(message)
    }
}
