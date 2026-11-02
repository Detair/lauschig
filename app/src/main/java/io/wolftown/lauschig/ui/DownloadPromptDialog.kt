package io.wolftown.lauschig.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.wolftown.lauschig.R
import io.wolftown.lauschig.download.DownloadPrompt
import io.wolftown.lauschig.ui.player.shortDuration

/** The open [DownloadPrompt] as a dialog; an outside tap does not close it, back does (= no download). */
@Composable
fun DownloadPromptDialog(
    prompt: DownloadPrompt,
    onMetered: (DownloadPrompt.Metered, now: Boolean, remember: Boolean) -> Unit,
    onContention: (DownloadPrompt.Contention, download: Boolean) -> Unit,
    onDismiss: (DownloadPrompt) -> Unit,
) {
    val properties = DialogProperties(dismissOnClickOutside = false)
    when (prompt) {
        is DownloadPrompt.Metered -> {
            var remember by rememberSaveable(prompt) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { onDismiss(prompt) },
                properties = properties,
                title = { Text(stringResource(R.string.download_metered_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.download_metered_message, prompt.title, Formatter.formatShortFileSize(LocalContext.current, prompt.bytes)))
                        Row(
                            Modifier.padding(top = 12.dp).toggleable(remember, role = Role.Checkbox) { remember = it }.testTag("promptRemember"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = remember, onCheckedChange = null)
                            Text(stringResource(R.string.download_remember_choice), Modifier.padding(start = 8.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onMetered(prompt, true, remember) }, modifier = Modifier.testTag("promptMeteredNow")) {
                        Text(stringResource(R.string.download_metered_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onMetered(prompt, false, remember) }, modifier = Modifier.testTag("promptMeteredWifi")) {
                        Text(stringResource(R.string.download_metered_wifi))
                    }
                },
                modifier = Modifier.testTag("downloadPrompt"),
            )
        }
        is DownloadPrompt.Contention -> AlertDialog(
            onDismissRequest = { onDismiss(prompt) },
            properties = properties,
            title = { Text(stringResource(R.string.download_contention_title)) },
            text = {
                Column {
                    Text(
                        prompt.etaSec?.let { stringResource(R.string.download_contention_message, prompt.title, shortDuration(it)) }
                            ?: stringResource(R.string.download_contention_message_unknown, prompt.title),
                    )
                    Text(
                        stringResource(R.string.download_contention_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onContention(prompt, false) }, modifier = Modifier.testTag("promptKeepStreaming")) {
                    Text(stringResource(R.string.download_contention_stream))
                }
            },
            dismissButton = {
                TextButton(onClick = { onContention(prompt, true) }, modifier = Modifier.testTag("promptDownload")) {
                    Text(stringResource(R.string.download_contention_download))
                }
            },
            modifier = Modifier.testTag("downloadPrompt"),
        )
    }
}
