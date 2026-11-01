package io.wolftown.lauschig.rmab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import io.wolftown.lauschig.R
import io.wolftown.lauschig.external.AudibleVolume

/** Small status symbol for a missing-volume tile; null when there is nothing to show (not requested, or a failed request). */
fun RmabStatus.tileIcon(): ImageVector? = when (this) {
    RmabStatus.WAITING -> Icons.Rounded.Schedule
    RmabStatus.ACTIVE -> Icons.Rounded.Downloading
    RmabStatus.DONE -> Icons.Rounded.CheckCircle
    RmabStatus.FAILED -> null
}

fun RmabStatus.label(): Int? = when (this) {
    RmabStatus.WAITING -> R.string.rmab_status_waiting
    RmabStatus.ACTIVE -> R.string.rmab_status_active
    RmabStatus.DONE -> R.string.rmab_status_done
    RmabStatus.FAILED -> null
}

/**
 * Request button for the missing-volume sheet. Nothing without a RMAB connection. "Request" (no entry, or a failed
 * one) asks for confirmation first; the other states are plain labels.
 */
@Composable
fun RmabMissingActions(volume: AudibleVolume, state: RmabUiState, onRequest: (AudibleVolume) -> Unit) {
    if (!state.connected) return
    val status = state.statuses[volume.asin]
    val label = status?.label()
    if (label != null) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("rmabStatus")) { Text(stringResource(label)) }
        Outcome(state.outcomes[volume.asin])
        return
    }
    var confirm by remember(volume.asin) { mutableStateOf(false) }
    Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().testTag("rmabRequest")) { Text(stringResource(R.string.rmab_request)) }
    Outcome(state.outcomes[volume.asin])
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.rmab_confirm_title)) },
            text = { Text(stringResource(R.string.rmab_confirm_message, volume.title)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRequest(volume) }, modifier = Modifier.testTag("rmabConfirm")) { Text(stringResource(R.string.rmab_request)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.setup_cancel)) } },
        )
    }
}

/** Result of the last request, under the button; errors in the error colour. */
@Composable
private fun Outcome(outcome: RmabOutcome?) {
    outcome ?: return
    val m = outcome.message
    Text(
        if (m.arg != null) stringResource(m.res, m.arg) else stringResource(m.res),
        style = MaterialTheme.typography.bodyMedium,
        color = if (outcome.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).testTag(if (outcome.isError) "rmabOutcomeError" else "rmabOutcome"),
    )
}

/** Settings section "ReadMeABook" (a slot in the settings screen). */
@Composable
fun RmabSettingsSection(viewModel: RmabSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RmabSettingsContent(state, viewModel::openDialog, viewModel::disconnect, viewModel::closeDialog, viewModel::connect, viewModel::confirmCleartext, viewModel::dismissCleartext)
}

@Composable
fun RmabSettingsContent(
    state: RmabSettingsState,
    onOpen: () -> Unit,
    onDisconnect: () -> Unit,
    onCloseDialog: () -> Unit,
    onConnect: (url: String, user: String, secret: String, tokenMode: Boolean) -> Unit,
    onConfirmCleartext: () -> Unit,
    onDismissCleartext: () -> Unit,
) {
    Text(
        stringResource(R.string.settings_section_rmab),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
    val config = state.config
    if (config == null) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.rmab_connect)) },
            supportingContent = { Text(stringResource(R.string.rmab_hint)) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("rmabConnect"),
        )
    } else {
        ListItem(
            headlineContent = {
                Text(if (config.username.isBlank()) stringResource(R.string.rmab_connected) else stringResource(R.string.rmab_connected_as, config.username))
            },
            supportingContent = {
                Text(
                    if (config.needsReconnect) stringResource(R.string.rmab_reconnect_hint) else config.url,
                    color = if (config.needsReconnect) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("rmabHint"),
                )
            },
            trailingContent = {
                ReconnectActions(config.needsReconnect, onOpen, onDisconnect)
            },
            modifier = Modifier.fillMaxWidth().testTag("rmabConnected"),
        )
    }
    if (state.dialogOpen) ConnectDialog(state, onCloseDialog, onConnect)
    state.cleartextHost?.let { host ->
        AlertDialog(
            onDismissRequest = onDismissCleartext,
            title = { Text(stringResource(R.string.setup_cleartext_title)) },
            text = { Text(stringResource(R.string.rmab_cleartext_message, host)) },
            confirmButton = { TextButton(onClick = onConfirmCleartext, modifier = Modifier.testTag("rmabCleartextConfirm")) { Text(stringResource(R.string.setup_cleartext_confirm)) } },
            dismissButton = { TextButton(onClick = onDismissCleartext) { Text(stringResource(R.string.setup_cancel)) } },
        )
    }
}

@Composable
private fun ReconnectActions(needsReconnect: Boolean, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    Column {
        if (needsReconnect) TextButton(onClick = onReconnect, modifier = Modifier.testTag("rmabReconnect")) { Text(stringResource(R.string.rmab_connect)) }
        TextButton(onClick = onDisconnect, modifier = Modifier.testTag("rmabDisconnect")) { Text(stringResource(R.string.rmab_disconnect)) }
    }
}

@Composable
private fun ConnectDialog(
    state: RmabSettingsState,
    onClose: () -> Unit,
    onConnect: (url: String, user: String, secret: String, tokenMode: Boolean) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") } // not saveable: passwords and tokens stay out of saved state
    var tokenMode by rememberSaveable { mutableStateOf(false) }
    val ready = url.isNotBlank() && secret.isNotBlank() && (tokenMode || user.isNotBlank()) && !state.loading
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.rmab_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.rmab_address)) },
                    placeholder = { Text("https://rmab.example.org") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("rmabUrl"),
                )
                if (!tokenMode) {
                    OutlinedTextField(
                        user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.rmab_username)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("rmabUser"),
                    )
                }
                OutlinedTextField(
                    secret, { secret = it }, singleLine = true,
                    label = { Text(stringResource(if (tokenMode) R.string.rmab_token else R.string.rmab_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("rmabSecret"),
                )
                TextButton(onClick = { tokenMode = !tokenMode; secret = "" }, modifier = Modifier.testTag("rmabToggleMode")) {
                    Text(stringResource(if (tokenMode) R.string.rmab_use_login else R.string.rmab_use_token))
                }
                state.error?.let {
                    Text(stringResource(it.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rmabError"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(url, user, secret, tokenMode) }, enabled = ready, modifier = Modifier.testTag("rmabDialogConnect")) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.rmab_connect))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.setup_cancel)) } },
    )
}
