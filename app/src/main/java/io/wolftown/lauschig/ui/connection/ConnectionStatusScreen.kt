package io.wolftown.lauschig.ui.connection

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.R
import io.wolftown.lauschig.connection.AddressRow
import io.wolftown.lauschig.connection.ConnectionDiagnostics
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ConnectionStatusUi
import io.wolftown.lauschig.connection.LinkState
import io.wolftown.lauschig.connection.NetworkDescription
import io.wolftown.lauschig.connection.ReconnectState

@Composable
fun ConnectionStatusRoute(onBack: () -> Unit, viewModel: ConnectionStatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ConnectionStatusScreen(
        state, onBack, viewModel::reconnect,
        onCopy = {
            val text = ConnectionDiagnostics.text(
                state, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                System.currentTimeMillis(),
            )
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lauschig", text))
            // Android 13+ confirms the copy itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.connection_copied, Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
fun ConnectionStatusScreen(
    state: ConnectionStatusUi,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("connectionScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge)
        }
        StatusCard(state)

        Section(R.string.connection_section_addresses)
        if (!state.signedIn) {
            ListItem(headlineContent = { Text(stringResource(R.string.connection_not_signed_in)) })
        }
        state.addresses.forEach { AddressItem(it, state.homeSsids, now) }

        Section(R.string.connection_section_network)
        ListItem(headlineContent = { Text(networkText(state.network)) }, modifier = Modifier.testTag("connectionNetwork"))

        Section(R.string.connection_section_checks)
        ListItem(
            headlineContent = {
                Text(
                    state.lastSuccess?.let { stringResource(R.string.connection_last_success, it.url.host, it.latencyMs, relative(it.at, now)) }
                        ?: stringResource(R.string.connection_last_success_none),
                )
            },
            supportingContent = state.lastFailure?.let { f ->
                { Text(stringResource(R.string.connection_last_error, relative(f.at, now), f.error ?: stringResource(R.string.connection_no_answer))) }
            },
        )

        Section(R.string.connection_section_sync)
        ListItem(
            headlineContent = {
                Text(
                    state.sync?.lastSyncAt?.let { stringResource(R.string.connection_last_sync, relative(it, now)) }
                        ?: stringResource(R.string.connection_last_sync_never),
                )
            },
            supportingContent = {
                Column {
                    state.sync?.let { Text(stringResource(R.string.connection_pending, it.dirtyProgress, it.unsentSessions)) }
                    Text(stringResource(R.string.connection_no_socket), style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = Modifier.testTag("connectionSync"),
        )

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val running = state.reconnect == ReconnectState.Running
            Button(onClick = onReconnect, enabled = state.signedIn && !running, modifier = Modifier.testTag("reconnect")) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.connection_reconnect))
            }
            OutlinedButton(onClick = onCopy, modifier = Modifier.testTag("copyDetails")) { Text(stringResource(R.string.connection_copy)) }
        }
        state.reconnect?.let { r ->
            Text(
                stringResource(
                    when (r) {
                        ReconnectState.Running -> R.string.connection_reconnecting
                        is ReconnectState.Done -> if (r.online) R.string.connection_reconnect_online else R.string.connection_reconnect_offline
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("reconnectResult"),
            )
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionStatusUi) {
    val (icon, title) = when (state.state) {
        LinkState.LOCAL -> Icons.Rounded.Home to R.string.connection_state_local
        LinkState.PUBLIC -> Icons.Rounded.Public to R.string.connection_state_public
        LinkState.OFFLINE -> Icons.Rounded.CloudOff to R.string.connection_state_offline
        LinkState.CHECKING -> Icons.Rounded.Sync to R.string.connection_state_checking
    }
    val online = state.state == LinkState.LOCAL || state.state == LinkState.PUBLIC
    Surface(
        color = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("connectionState"),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                val detail = state.baseUrl?.let { ConnectionDiagnostics.safe(it) }
                    ?: if (state.noNetwork) stringResource(R.string.connection_network_none) else null
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.username?.let { Text(stringResource(R.string.connection_signed_in_as, it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun AddressItem(row: AddressRow, homeSsids: Set<String>, now: Long) {
    val label = stringResource(if (row.via == ConnectionState.Via.LOCAL) R.string.connection_local_address else R.string.connection_public_address)
    val probe = row.probe
    val status = when {
        probe == null -> stringResource(R.string.connection_probe_none)
        probe.ok -> stringResource(R.string.connection_probe_ok, probe.latencyMs, relative(probe.at, now))
        else -> stringResource(R.string.connection_probe_failed, relative(probe.at, now)) + (probe.error?.let { "\n$it" } ?: "")
    }
    ListItem(
        overlineContent = { Text(if (row.inUse) "$label · ${stringResource(R.string.connection_in_use)}" else label) },
        headlineContent = { Text(ConnectionDiagnostics.safe(row.url)) },
        supportingContent = {
            Column {
                Text(status)
                if (row.onlyAtHome) Text(stringResource(R.string.connection_only_home, homeSsids.sorted().joinToString()))
            }
        },
        modifier = Modifier.testTag("address:${row.via}"),
    )
}

@Composable
private fun networkText(n: NetworkDescription?): String {
    if (n == null) return stringResource(R.string.connection_network_none)
    val parts = n.transports.sortedBy { it.ordinal }.map {
        stringResource(
            when (it) {
                NetworkDescription.Transport.WIFI -> R.string.connection_wifi
                NetworkDescription.Transport.CELLULAR -> R.string.connection_cellular
                NetworkDescription.Transport.ETHERNET -> R.string.connection_ethernet
                NetworkDescription.Transport.VPN -> R.string.connection_vpn
                NetworkDescription.Transport.BLUETOOTH -> R.string.connection_bluetooth
                NetworkDescription.Transport.OTHER -> R.string.connection_other
            },
        )
    }
    val extra = listOfNotNull(
        stringResource(if (n.metered) R.string.connection_metered else R.string.connection_unmetered),
        stringResource(R.string.connection_not_validated).takeIf { !n.validated },
    )
    return (parts.joinToString(" + ") + " · " + extra.joinToString(" · "))
}

private fun relative(at: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(at, now.coerceAtLeast(at), DateUtils.SECOND_IN_MILLIS).toString()

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
