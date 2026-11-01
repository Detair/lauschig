package io.wolftown.lauschig.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNetworkInfo @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : NetworkInfo {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override val isConnected: Flow<Boolean> = callbackFlow {
        fun connected(caps: NetworkCapabilities?) =
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(connected(cm.getNetworkCapabilities(network)))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(connected(caps))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(connected(cm.getNetworkCapabilities(cm.activeNetwork)))
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    /** Only called when SSID binding is active. Returns null without permission or when not on Wi-Fi. */
    @Suppress("DEPRECATION")
    override suspend fun currentSsid(): String? {
        if (!hasWifiPermission()) return null
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val ssid = wm.connectionInfo?.ssid ?: return null
        if (ssid == WifiManager.UNKNOWN_SSID) return null
        return ssid.removeSurrounding("\"").ifEmpty { null }
    }

    private fun hasWifiPermission(): Boolean {
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            (Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.NEARBY_WIFI_DEVICES))
    }
}
