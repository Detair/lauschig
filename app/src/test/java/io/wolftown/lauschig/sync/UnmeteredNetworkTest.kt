package io.wolftown.lauschig.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnmeteredNetworkTest {
    private val wifi = NetworkFacts(vpn = false, wifiOrEthernet = true, notMetered = true, validated = true)
    private val meteredWifi = wifi.copy(notMetered = false) // a hotspot, or a Wi-Fi the user marked metered
    private val cellular = NetworkFacts(vpn = false, wifiOrEthernet = false, notMetered = false, validated = true)
    private val vpn = NetworkFacts(vpn = true, wifiOrEthernet = false, notMetered = false, validated = true) // NetBird-like
    private val vpnDeclaredUnmetered = vpn.copy(notMetered = true)

    @Test fun wifi_isUnmetered() = assertTrue(UnmeteredNetwork.decide(wifi, listOf(wifi)))

    @Test fun aVpnOverWifi_isUnmetered_evenWhenTheVpnSaysMetered() {
        assertTrue(UnmeteredNetwork.decide(vpn, listOf(vpn, wifi)))
        assertTrue(UnmeteredNetwork.decide(vpnDeclaredUnmetered, listOf(vpnDeclaredUnmetered, wifi)))
        assertFalse(UnmeteredNetwork.decide(vpn, listOf(vpn, wifi.copy(validated = false)))) // a Wi-Fi without internet
    }

    @Test fun aVpnOverCellular_orCellular_orAMeteredWifi_isMetered() {
        assertFalse(UnmeteredNetwork.decide(vpn, listOf(vpn, cellular)))
        assertFalse(UnmeteredNetwork.decide(vpn, listOf(vpn, meteredWifi, cellular)))
        assertFalse(UnmeteredNetwork.decide(cellular, listOf(cellular, wifi))) // the default is cellular: traffic goes there
        assertFalse(UnmeteredNetwork.decide(meteredWifi, listOf(meteredWifi)))
        assertFalse(UnmeteredNetwork.decide(null, listOf(wifi))) // no default network
    }
}
