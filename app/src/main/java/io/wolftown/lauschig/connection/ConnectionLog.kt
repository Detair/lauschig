package io.wolftown.lauschig.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/** One reachability probe of [url]: answered or not, how long it took, when, and why it failed (never secrets). */
data class ProbeRecord(val url: HttpUrl, val ok: Boolean, val latencyMs: Long, val at: Long, val error: String? = null)

/** What [ReachabilityProbe.check] found out. */
data class ProbeOutcome(val ok: Boolean, val error: String? = null)

/**
 * The connection checks of this process for the Verbindungsstatus window: the last probe per address and the last
 * decision of [AddressResolver]. In memory only (a fresh process starts empty, the first probe fills it).
 */
data class ConnectionLogState(
    /** By address (`HttpUrl.toString()`). */
    val probes: Map<String, ProbeRecord> = emptyMap(),
    val resolved: ConnectionState? = null,
    val resolvedAt: Long? = null,
    /** The last decision was "offline" because there was no network at all (nothing was probed). */
    val noNetwork: Boolean = false,
) {
    val lastSuccess: ProbeRecord? get() = probes.values.filter { it.ok }.maxByOrNull { it.at }
    val lastFailure: ProbeRecord? get() = probes.values.filter { !it.ok }.maxByOrNull { it.at }
}

@Singleton
class ConnectionLog @Inject constructor() {
    private val _state = MutableStateFlow(ConnectionLogState())
    val state: StateFlow<ConnectionLogState> = _state

    /** Another account: the addresses and answers here were the last one's. */
    fun clear() { _state.value = ConnectionLogState() }

    fun probed(record: ProbeRecord) = _state.update { it.copy(probes = it.probes + (record.url.toString() to record)) }

    fun resolved(state: ConnectionState, at: Long, noNetwork: Boolean = false) =
        _state.update { it.copy(resolved = state, resolvedAt = at, noNetwork = noNetwork) }
}
