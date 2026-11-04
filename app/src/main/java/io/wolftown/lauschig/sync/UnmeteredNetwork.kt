package io.wolftown.lauschig.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the unmetered decision needs to know of one network (from its [NetworkCapabilities]). */
data class NetworkFacts(val vpn: Boolean, val wifiOrEthernet: Boolean, val notMetered: Boolean, val validated: Boolean)

/**
 * Whether large downloads may run now. A VPN as the default network (NetBird, how this family reaches its server) is
 * metered unless its app says otherwise, even on home Wi-Fi; so behind a VPN the decision looks at the networks under it:
 * a validated, unmetered Wi-Fi or Ethernet counts.
 */
object UnmeteredNetwork {
    fun decide(active: NetworkFacts?, all: List<NetworkFacts>): Boolean {
        if (active == null) return false
        if (active.notMetered) return true
        return active.vpn && all.any { !it.vpn && it.wifiOrEthernet && it.notMetered && it.validated }
    }

    fun facts(caps: NetworkCapabilities) = NetworkFacts(
        vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    @Suppress("DEPRECATION") // allNetworks: the only way to see the networks under a VPN without a callback
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(::facts)
            decide(active, cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let(::facts) })
        } catch (_: SecurityException) {
            false
        }
    }
}
