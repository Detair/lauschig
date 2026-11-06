package io.wolftown.lauschig.connection

import io.wolftown.lauschig.connection.ConnectionState.Via
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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

    /** Like [isReachable], with the reason of a failure for the Verbindungsstatus window (no headers, no tokens). */
    suspend fun check(url: HttpUrl): ProbeOutcome = ProbeOutcome(isReachable(url))
}

/** How the device is online right now (Verbindungsstatus window). */
data class NetworkDescription(val transports: Set<Transport>, val metered: Boolean, val validated: Boolean) {
    enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, OTHER }
}

interface NetworkInfo {
    /** Emits on every change of the default network; false means no usable network. */
    val isConnected: Flow<Boolean>

    /** SSID of the current Wi-Fi, or null if unknown, not on Wi-Fi or not permitted. */
    suspend fun currentSsid(): String?

    /** The default network now; null without a network (or where it cannot be read). */
    fun describe(): NetworkDescription? = null
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
    /** Every probe and decision, for the Verbindungsstatus window. */
    private val log: ConnectionLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Bumped by [recheck]: the running [states] loops start over with a fresh probe. */
    private val rechecks = MutableStateFlow(0)

    suspend fun resolve(a: ServerAddresses): ConnectionState {
        if (!networkInfo.isConnected.first()) {
            log?.resolved(ConnectionState.Offline, clock(), noNetwork = true)
            return ConnectionState.Offline
        }
        val state = choose(a, ::probeLogged)
        log?.resolved(state, clock())
        return state
    }

    /**
     * Like [resolve] for addresses that are not the signed-in account's (the server of a saved profile): the same decision,
     * but nothing goes to the [log], which shows the signed-in account's connection.
     */
    suspend fun resolveElsewhere(a: ServerAddresses): ConnectionState =
        if (networkInfo.isConnected.first()) choose(a) { probe.check(it).ok } else ConnectionState.Offline

    private suspend fun choose(a: ServerAddresses, answers: suspend (HttpUrl) -> Boolean): ConnectionState {
        val tryLocal = a.local != null && (a.homeSsids.isEmpty() || networkInfo.currentSsid() in a.homeSsids)
        return when {
            tryLocal && answers(a.local!!) -> ConnectionState.Online(a.local, Via.LOCAL)
            a.public != null && answers(a.public) -> ConnectionState.Online(a.public, Via.PUBLIC)
            else -> ConnectionState.Offline
        }
    }

    /** Probes [url] and writes the result to the [log]. */
    private suspend fun probeLogged(url: HttpUrl): Boolean {
        val start = clock()
        val outcome = probe.check(url)
        val end = clock()
        log?.probed(ProbeRecord(url, outcome.ok, (end - start).coerceAtLeast(0), end, outcome.error))
        return outcome.ok
    }

    /** Running [states] loops probe again right away (instead of waiting for the next offline re-check). */
    fun recheck() = rechecks.update { it + 1 }

    /**
     * "Neu verbinden": probes every configured address at once (the local one also outside the home Wi-Fi, so the
     * window shows which one answers), then [recheck]s, so the shared connection state follows right away.
     */
    suspend fun reconnect(a: ServerAddresses): List<ProbeRecord> = coroutineScope {
        val urls = listOfNotNull(a.local, a.public).distinct()
        val results = urls.map { url ->
            async {
                val start = clock()
                val outcome = probe.check(url)
                val end = clock()
                ProbeRecord(url, outcome.ok, (end - start).coerceAtLeast(0), end, outcome.error).also { log?.probed(it) }
            }
        }.awaitAll()
        recheck()
        results
    }

    /**
     * Re-resolves on every network change, and while offline with a network after [firstOfflineRecheck], doubling up
     * to [offlineRecheck]. Going from online to offline while the network stays up needs [CONFIRM_ATTEMPTS] more
     * failed probes: a single failure is far more often a saturated link (a download running) than a vanished server.
     * [recheck] starts the loop over as if the network had changed.
     */
    fun states(a: ServerAddresses): Flow<ConnectionState> = channelFlow {
        var last: ConnectionState? = null
        combine(networkInfo.isConnected, rechecks) { connected, _ -> connected }.collectLatest { connected ->
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
