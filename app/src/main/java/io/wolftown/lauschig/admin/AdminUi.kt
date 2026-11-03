package io.wolftown.lauschig.admin

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.absapi.admin.OnlineUsers
import io.wolftown.lauschig.absapi.admin.OpenSession
import io.wolftown.lauschig.db.entities.LibraryEntity
import kotlinx.coroutines.flow.Flow

/** Settings → "Server-Verwaltung": only for admins, disabled without a connection. */
@Composable
fun AdminSettingsEntry(onOpen: () -> Unit, viewModel: AdminAccessViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    AdminSettingsEntryContent(access, onOpen)
}

@Composable
fun AdminSettingsEntryContent(access: AdminAccess, onOpen: () -> Unit) {
    if (!access.admin) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.admin_title)) },
        supportingContent = { Text(stringResource(if (access.online) R.string.admin_entry_hint else R.string.admin_offline_hint)) },
        leadingContent = { Icon(Icons.Rounded.AdminPanelSettings, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = access.online, onClick = onOpen).testTag("openServerAdmin"),
    )
}

@Composable
fun ServerAdminRoute(onBack: () -> Unit, viewModel: ServerAdminViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AdminMessages(viewModel.messages)
    ServerAdminScreen(state, onBack, viewModel::scan, viewModel::refreshOnline)
}

@Composable
fun ServerAdminScreen(state: ServerAdminUi, onBack: () -> Unit, onScan: (LibraryEntity) -> Unit, onRefreshOnline: () -> Unit, now: Long = System.currentTimeMillis()) {
    var confirm by remember { mutableStateOf<LibraryEntity?>(null) }
    val enabled = state.access.admin && state.access.online
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("serverAdmin")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.admin_title), style = MaterialTheme.typography.titleLarge)
        }
        if (!state.access.online) {
            Text(stringResource(R.string.admin_offline_hint), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        }

        Section(R.string.admin_section_libraries)
        state.libraries.forEach { lib ->
            ListItem(
                headlineContent = { Text(lib.name) },
                trailingContent = {
                    if (lib.id in state.scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else OutlinedButton(onClick = { confirm = lib }, enabled = enabled, modifier = Modifier.testTag("scan:${lib.id}")) {
                        Text(stringResource(R.string.admin_scan))
                    }
                },
            )
        }

        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Section(R.string.admin_section_online) }
            IconButton(onClick = onRefreshOnline, enabled = enabled, modifier = Modifier.testTag("refreshOnline")) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.admin_refresh))
            }
        }
        when (val o = state.online) {
            null -> Unit
            OnlineState.Loading -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
            is OnlineState.Problem -> Text(problemText(LocalContext.current, o.result), Modifier.padding(horizontal = 16.dp))
            is OnlineState.Loaded -> OnlineList(o.users, state.selfId, state.selfName)
        }
        Text(
            stringResource(R.string.admin_online_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
    }
    confirm?.let { lib ->
        ConfirmDialog(
            title = stringResource(R.string.admin_scan_library_title),
            text = stringResource(R.string.admin_scan_library_message, lib.name),
            onConfirm = { confirm = null; onScan(lib) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun OnlineList(users: OnlineUsers, selfId: String?, selfName: String?) {
    val names = users.usersOnline.associate { it.id to it.username } + listOfNotNull(selfId?.let { id -> selfName?.let { id to it } })
    if (users.usersOnline.isEmpty() && users.openSessions.isEmpty()) {
        ListItem(headlineContent = { Text(stringResource(R.string.admin_online_none)) }, modifier = Modifier.testTag("onlineNone"))
        return
    }
    users.usersOnline.forEach { u ->
        ListItem(
            headlineContent = { Text(u.username) },
            supportingContent = {
                Text(u.session?.let { stringResource(R.string.admin_listening, sessionTitle(it), position(it)) } ?: stringResource(R.string.admin_not_listening))
            },
            modifier = Modifier.testTag("online:${u.username}"),
        )
    }
    val others = users.openSessions.filter { s -> users.usersOnline.none { it.session?.id == s.id } }
    if (others.isNotEmpty()) {
        Text(stringResource(R.string.admin_sessions_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        others.forEach { s ->
            ListItem(
                headlineContent = { Text(sessionTitle(s)) },
                supportingContent = {
                    Text(
                        listOfNotNull(names[s.userId] ?: stringResource(R.string.admin_unknown_user), device(s), position(s))
                            .joinToString(" · "),
                    )
                },
            )
        }
    }
}

private fun sessionTitle(s: OpenSession) = listOfNotNull(s.displayTitle, s.displayAuthor).joinToString(" – ").ifEmpty { "?" }

private fun device(s: OpenSession): String? = s.deviceInfo?.let { d ->
    listOfNotNull(d.clientName, d.deviceName ?: listOfNotNull(d.manufacturer, d.model).joinToString(" ").ifBlank { null } ?: d.browserName)
        .joinToString(", ").ifBlank { null }
}

private fun position(s: OpenSession): String {
    fun fmt(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0)
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }
    return if (s.duration > 0) "${fmt(s.currentTime)} / ${fmt(s.duration)}" else fmt(s.currentTime)
}

/** Detail page: rescan / re-match a title (admins, server titles). */
@Composable
fun ItemAdminMenu(title: String, viewModel: ItemAdminViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AdminMessages(viewModel.messages)
    ItemAdminMenuContent(state, title, viewModel::rescan, viewModel::rematch)
}

private enum class ItemAction { RESCAN, REMATCH }

@Composable
fun ItemAdminMenuContent(state: ItemAdminUi, title: String, onRescan: () -> Unit, onRematch: () -> Unit) {
    if (!state.visible) return
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ItemAction?>(null) }
    Box {
        IconButton(onClick = { open = true }, enabled = !state.busy, modifier = Modifier.testTag("itemAdmin")) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.AdminPanelSettings, stringResource(R.string.admin_item_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.admin_rescan_item)) },
                enabled = state.online,
                onClick = { open = false; confirm = ItemAction.RESCAN },
                modifier = Modifier.testTag("adminRescan"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.admin_match_item)) },
                enabled = state.online,
                onClick = { open = false; confirm = ItemAction.REMATCH },
                modifier = Modifier.testTag("adminRematch"),
            )
            if (!state.online) DropdownMenuItem(text = { Text(stringResource(R.string.admin_offline_hint)) }, enabled = false, onClick = {})
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            title = stringResource(if (action == ItemAction.RESCAN) R.string.admin_rescan_item else R.string.admin_match_item),
            text = stringResource(if (action == ItemAction.RESCAN) R.string.admin_rescan_item_message else R.string.admin_match_item_message, title),
            onConfirm = {
                confirm = null
                if (action == ItemAction.RESCAN) onRescan() else onRematch()
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("adminConfirm")) { Text(stringResource(R.string.admin_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.admin_cancel)) } },
    )
}

/** Shows each admin result once as a toast. */
@Composable
private fun AdminMessages(messages: Flow<AdminEvent>) {
    val context = LocalContext.current
    LaunchedEffect(messages) {
        messages.collect { Toast.makeText(context, eventText(context, it), Toast.LENGTH_LONG).show() }
    }
}

fun eventText(context: Context, e: AdminEvent): String = when (e) {
    is AdminEvent.ScanStarted -> context.getString(R.string.admin_scan_started, e.library)
    is AdminEvent.ItemScanned -> context.getString(
        when (e.result) {
            "UPDATED" -> R.string.admin_scan_updated
            "UPTODATE" -> R.string.admin_scan_uptodate
            "REMOVED" -> R.string.admin_scan_removed
            else -> R.string.admin_scan_done
        },
    )
    is AdminEvent.Matched -> when (val o = e.outcome) {
        is MatchOutcome.Matched -> context.getString(if (o.updated) R.string.admin_matched else R.string.admin_matched_nothing)
        is MatchOutcome.NoMatch -> context.getString(R.string.admin_no_match, o.warning)
        MatchOutcome.NoProvider -> context.getString(R.string.admin_no_provider)
    }
    is AdminEvent.Problem -> problemText(context, e.result)
}

fun problemText(context: Context, r: AdminResult<Nothing>): String = when (r) {
    AdminResult.Forbidden -> context.getString(R.string.admin_forbidden)
    AdminResult.Offline -> context.getString(R.string.admin_offline)
    is AdminResult.Failed -> context.getString(R.string.admin_failed, r.message ?: "?")
    is AdminResult.Ok -> ""
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
