package io.wolftown.lauschig.connection

import io.wolftown.lauschig.account.Account
import okhttp3.HttpUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Progress sync as far as the device knows it (Lauschig has no socket.io connection to show instead). */
data class SyncInfo(val lastSyncAt: Long?, val dirtyProgress: Int, val unsentSessions: Int)

enum class LinkState { LOCAL, PUBLIC, OFFLINE, CHECKING }

/** One configured address of the account. */
data class AddressRow(
    val via: ConnectionState.Via,
    val url: HttpUrl,
    /** The app talks to this address right now. */
    val inUse: Boolean,
    val probe: ProbeRecord?,
    /** Local address bound to home Wi-Fi names, and the device is not on one of them (the app does not try it). */
    val onlyAtHome: Boolean,
)

/** "Neu verbinden": null = not pressed yet. */
sealed interface ReconnectState {
    data object Running : ReconnectState
    data class Done(val online: Boolean) : ReconnectState
}

/** Everything the Verbindungsstatus window shows. */
data class ConnectionStatusUi(
    val signedIn: Boolean = false,
    val username: String? = null,
    val serverId: String? = null,
    val state: LinkState = LinkState.CHECKING,
    val baseUrl: HttpUrl? = null,
    val addresses: List<AddressRow> = emptyList(),
    val homeSsids: Set<String> = emptySet(),
    val network: NetworkDescription? = null,
    val noNetwork: Boolean = false,
    val lastSuccess: ProbeRecord? = null,
    val lastFailure: ProbeRecord? = null,
    val lastCheckAt: Long? = null,
    val sync: SyncInfo? = null,
    val reconnect: ReconnectState? = null,
)

object ConnectionStatusMapper {
    fun map(
        account: Account?,
        connection: ConnectionState,
        checking: Boolean,
        log: ConnectionLogState,
        network: NetworkDescription?,
        onHomeWifi: Boolean?,
        sync: SyncInfo?,
        reconnect: ReconnectState?,
    ): ConnectionStatusUi {
        val online = connection as? ConnectionState.Online
        val state = when {
            online?.via == ConnectionState.Via.LOCAL -> LinkState.LOCAL
            online != null -> LinkState.PUBLIC
            checking || reconnect == ReconnectState.Running -> LinkState.CHECKING
            else -> LinkState.OFFLINE
        }
        val a = account?.addresses
        val rows = listOfNotNull(
            a?.local?.let { url ->
                AddressRow(
                    ConnectionState.Via.LOCAL, url, inUse = online?.baseUrl == url, probe = log.probes[url.toString()],
                    onlyAtHome = a.homeSsids.isNotEmpty() && onHomeWifi != true,
                )
            },
            a?.public?.let { url ->
                AddressRow(ConnectionState.Via.PUBLIC, url, inUse = online?.baseUrl == url, probe = log.probes[url.toString()], onlyAtHome = false)
            },
        )
        return ConnectionStatusUi(
            signedIn = account != null,
            username = account?.username,
            serverId = account?.serverId,
            state = state,
            baseUrl = online?.baseUrl,
            addresses = rows,
            homeSsids = a?.homeSsids.orEmpty(),
            network = network,
            noNetwork = network == null && log.noNetwork,
            lastSuccess = log.lastSuccess,
            lastFailure = log.lastFailure,
            lastCheckAt = log.resolvedAt,
            sync = sync,
            reconnect = reconnect,
        )
    }
}

/**
 * The plain-text diagnosis "Details kopieren" puts on the clipboard, for family debugging. Technical English, one
 * fact per line. Never contains tokens or passwords: none of its inputs carries them, and user info in an address
 * (`https://user:pass@host`) is stripped.
 */
object ConnectionDiagnostics {
    fun text(ui: ConnectionStatusUi, appVersion: String, androidVersion: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        fun at(ms: Long?) = ms?.let { time.format(Instant.ofEpochMilli(it)) } ?: "never"
        fun probe(p: ProbeRecord?) = when {
            p == null -> "not checked"
            p.ok -> "answered in ${p.latencyMs} ms at ${at(p.at)}"
            else -> "no answer after ${p.latencyMs} ms at ${at(p.at)}" + (p.error?.let { ": $it" } ?: "")
        }
        return buildString {
            appendLine("Lauschig $appVersion – Android $androidVersion")
            appendLine("Time: ${at(now)}")
            appendLine("Account: " + if (ui.signedIn) "${ui.username} (server ${ui.serverId})" else "not signed in")
            appendLine("State: " + when (ui.state) {
                LinkState.LOCAL -> "online, local address"
                LinkState.PUBLIC -> "online, public address"
                LinkState.OFFLINE -> "offline"
                LinkState.CHECKING -> "checking"
            } + (ui.baseUrl?.let { " – ${safe(it)}" } ?: ""))
            if (ui.addresses.isEmpty()) appendLine("Addresses: none")
            ui.addresses.forEach { row ->
                val label = if (row.via == ConnectionState.Via.LOCAL) "Local" else "Public"
                val extra = listOfNotNull("in use".takeIf { row.inUse }, "only on home Wi-Fi, not there now".takeIf { row.onlyAtHome })
                appendLine("$label address: ${safe(row.url)}" + (if (extra.isEmpty()) "" else " (${extra.joinToString()})") + " – ${probe(row.probe)}")
            }
            if (ui.homeSsids.isNotEmpty()) appendLine("Home Wi-Fi: ${ui.homeSsids.sorted().joinToString()}")
            appendLine("Network: " + (ui.network?.let { n ->
                n.transports.joinToString("+") { it.name.lowercase() } + (if (n.metered) ", metered" else ", not metered") +
                    (if (n.validated) ", internet validated" else ", internet not validated")
            } ?: "none"))
            appendLine("Last check: ${at(ui.lastCheckAt)}")
            appendLine("Last success: " + (ui.lastSuccess?.let { "${safe(it.url)} in ${it.latencyMs} ms at ${at(it.at)}" } ?: "none"))
            appendLine("Last error: " + (ui.lastFailure?.let { "${safe(it.url)} at ${at(it.at)}: ${it.error ?: "no answer"}" } ?: "none"))
            appendLine("Socket: none (Lauschig syncs over HTTP only)")
            appendLine("Progress sync: " + (ui.sync?.let { "last ${at(it.lastSyncAt)}, waiting: ${it.dirtyProgress} progress, ${it.unsentSessions} sessions" } ?: "unknown"))
        }.trimEnd()
    }

    /** The address without user info, query or fragment. */
    fun safe(url: HttpUrl): String = url.newBuilder().username("").password("").query(null).fragment(null).build().toString()
}
