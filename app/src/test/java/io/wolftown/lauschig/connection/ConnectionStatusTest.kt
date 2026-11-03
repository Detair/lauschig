package io.wolftown.lauschig.connection

import io.wolftown.lauschig.account.Account
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ConnectionStatusTest {
    private val local = "http://192.168.1.5:13378/".toHttpUrl()
    private val public = "https://fabian:geheim@abs.example.org/".toHttpUrl()
    private val account = Account("0123456789abcdef", "u1", "fabian", false, ServerAddresses(local, public, setOf("Home")), emptySet())
    private val log = ConnectionLogState(
        probes = mapOf(
            local.toString() to ProbeRecord(local, false, 3_001, 10_000, "SocketTimeoutException: connect timed out"),
            public.toString() to ProbeRecord(public, true, 120, 11_000),
        ),
        resolved = ConnectionState.Online(public, ConnectionState.Via.PUBLIC),
        resolvedAt = 11_000,
    )
    private val wifi = NetworkDescription(setOf(NetworkDescription.Transport.WIFI, NetworkDescription.Transport.VPN), metered = false, validated = true)

    private fun ui(
        connection: ConnectionState = ConnectionState.Online(public, ConnectionState.Via.PUBLIC),
        checking: Boolean = false,
        reconnect: ReconnectState? = null,
        onHome: Boolean? = false,
    ) = ConnectionStatusMapper.map(account, connection, checking, log, wifi, onHome, SyncInfo(9_000, 2, 1), reconnect)

    @Test fun state_followsTheConnection() {
        assertEquals(LinkState.PUBLIC, ui().state)
        assertEquals(LinkState.LOCAL, ui(ConnectionState.Online(local, ConnectionState.Via.LOCAL)).state)
        assertEquals(LinkState.OFFLINE, ui(ConnectionState.Offline).state)
        assertEquals(LinkState.CHECKING, ui(ConnectionState.Offline, checking = true).state)
        assertEquals(LinkState.CHECKING, ui(ConnectionState.Offline, reconnect = ReconnectState.Running).state)
    }

    @Test fun addresses_showProbe_inUse_andHomeBinding() {
        val rows = ui().addresses
        assertEquals(2, rows.size)
        val l = rows.first { it.via == ConnectionState.Via.LOCAL }
        val p = rows.first { it.via == ConnectionState.Via.PUBLIC }
        assertFalse(l.inUse)
        assertTrue(l.onlyAtHome)
        assertEquals("SocketTimeoutException: connect timed out", l.probe!!.error)
        assertTrue(p.inUse)
        assertFalse(ui(onHome = true).addresses.first { it.via == ConnectionState.Via.LOCAL }.onlyAtHome)
    }

    @Test fun notSignedIn_hasNoAddresses() {
        val u = ConnectionStatusMapper.map(null, ConnectionState.Offline, false, ConnectionLogState(), null, null, null, null)
        assertFalse(u.signedIn)
        assertTrue(u.addresses.isEmpty())
    }

    @Test fun diagnostics_coverEverySection_withoutSecrets() {
        val text = ConnectionDiagnostics.text(ui(), "0.1.0 (1)", "15 (API 35)", 12_000, ZoneId.of("UTC"))
        listOf(
            "Lauschig 0.1.0 (1) – Android 15 (API 35)", "Account: fabian (server 0123456789abcdef)",
            "State: online, public address – https://abs.example.org/",
            "Local address: http://192.168.1.5:13378/ (only on home Wi-Fi, not there now) – no answer after 3001 ms",
            "SocketTimeoutException: connect timed out", "Public address: https://abs.example.org/ (in use) – answered in 120 ms",
            "Home Wi-Fi: Home", "Network: wifi+vpn, not metered, internet validated", "Socket: none",
            "Progress sync: last 1970-01-01 00:00:09, waiting: 2 progress, 1 sessions",
        ).forEach { assertTrue("missing: $it\n$text", text.contains(it)) }
        assertFalse(text.contains("geheim"))
        assertFalse(text.contains("fabian:"))
    }
}
