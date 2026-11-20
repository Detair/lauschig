package io.wolftown.lauschig.tvsetup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.Inet4Address
import java.net.Socket
import javax.inject.Inject

/** The device's place in the home network. */
interface HomeNetwork : HomeSockets {
    /** The device's own home address; null: none. */
    fun ownAddress(): Inet4Address?
}

/**
 * Only Wi-Fi and Ethernet count, and never a VPN: the token must not leave the house through mobile data or a tunnel,
 * even when that is the default route. Sockets are bound to such a network, not to whatever the system prefers.
 */
class AndroidHomeNetwork @Inject constructor(@ApplicationContext context: Context) : HomeNetwork {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun ownAddress(): Inet4Address? = homeNetworks().firstOrNull()?.second

    override fun open(): Socket? {
        val network = homeNetworks().firstOrNull()?.first ?: return null
        return try {
            network.socketFactory.createSocket()
        } catch (_: IOException) {
            null
        }
    }

    /** Each home network with its first private IPv4 address, the active network first. */
    private fun homeNetworks(): List<Pair<Network, Inet4Address>> {
        val manager = connectivity ?: return emptyList()
        val active = manager.activeNetwork
        // allNetworks is deprecated for callbacks, but a one-off look at the current networks is what this needs.
        @Suppress("DEPRECATION")
        val all = manager.allNetworks.sortedBy { if (it == active) 0 else 1 }
        return all.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val address = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { TvSetupCodes.homeAddress(it.hostAddress.orEmpty()) != null }
                ?: return@mapNotNull null
            network to address
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TvSetupModule {
    @Binds abstract fun homeNetwork(impl: AndroidHomeNetwork): HomeNetwork

    companion object {
        @Provides fun handoverSender(network: HomeNetwork): HandoverSender = HandoverSender(network)
    }
}
