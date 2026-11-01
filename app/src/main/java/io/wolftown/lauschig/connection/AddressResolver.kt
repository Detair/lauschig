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

/** Checks whether the ABS server answers at [url] (3 s timeout; any failure means false). */
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
 * The re-check interval while offline is virtual-time friendly (uses [delay]).
 */
class AddressResolver(
    private val probe: ReachabilityProbe,
    private val networkInfo: NetworkInfo,
    private val offlineRecheck: Duration = 60.seconds,
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

    /** Re-resolves on every network change, and every [offlineRecheck] while offline. */
    fun states(a: ServerAddresses): Flow<ConnectionState> = channelFlow {
        networkInfo.isConnected.collectLatest {
            while (true) {
                val state = resolve(a)
                send(state)
                if (state is ConnectionState.Online || !it) break
                delay(offlineRecheck)
            }
        }
    }.distinctUntilChanged()
}
