package io.wolftown.lauschig.connection

import io.wolftown.lauschig.connection.ConnectionState.Via
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Checks whether the ABS server answers at [url] (see [OkHttpReachabilityProbe] for the timeouts; any failure means false). */
fun interface ReachabilityProbe {
    suspend fun isReachable(url: HttpUrl): Boolean
}

interface NetworkInfo {
    /** Emits on every change of the default network; false means no usable network. */
    val isConnected: Flow<Boolean>

    /** SSID of the current Wi-Fi, or null if unknown, not on Wi-Fi or not permitted. */
    suspend fun currentSsid(): String?
}

/**
 * Decides whether the app talks to the local or the public server address, or is offline.
 * The re-check intervals while offline are virtual-time friendly (use [delay]).
 */
class AddressResolver(
    private val probe: ReachabilityProbe,
    private val networkInfo: NetworkInfo,
    /** First re-check while offline with a network; doubles up to [offlineRecheck]. */
    private val firstOfflineRecheck: Duration = 5.seconds,
    private val offlineRecheck: Duration = 60.seconds,
    /** Pause between the extra probes that confirm a drop from online to offline. */
    private val confirmDelay: Duration = 2.seconds,
) {
    suspend fun resolve(a: ServerAddresses): ConnectionState {
        if (!networkInfo.isConnected.first()) return ConnectionState.Offline

        val tryLocal = a.local != null && (a.homeSsids.isEmpty() || networkInfo.currentSsid() in a.homeSsids)
        if (tryLocal && probe.isReachable(a.local!!)) {
            return ConnectionState.Online(a.local, Via.LOCAL)
        }
        if (a.public != null && probe.isReachable(a.public)) {
            return ConnectionState.Online(a.public, Via.PUBLIC)
        }
        return ConnectionState.Offline
    }

    /**
     * Re-resolves on every network change, and while offline with a network after [firstOfflineRecheck], doubling up
     * to [offlineRecheck]. Going from online to offline while the network stays up needs [CONFIRM_ATTEMPTS] more
     * failed probes: a single failure is far more often a saturated link (a download running) than a vanished server.
     */
    fun states(a: ServerAddresses): Flow<ConnectionState> = channelFlow {
        var last: ConnectionState? = null
        networkInfo.isConnected.collectLatest { connected ->
            var wait = firstOfflineRecheck
            while (true) {
                var state = resolve(a)
                var attempts = 0
                while (state is ConnectionState.Offline && last is ConnectionState.Online && connected && attempts < CONFIRM_ATTEMPTS) {
                    delay(confirmDelay)
                    attempts++
                    state = resolve(a)
                }
                last = state
                send(state)
                if (state is ConnectionState.Online || !connected) break
                delay(wait)
                wait = (wait * 2).coerceAtMost(offlineRecheck)
            }
        }
    }.distinctUntilChanged()

    private companion object {
        const val CONFIRM_ATTEMPTS = 2
    }
}
