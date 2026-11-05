package io.wolftown.lauschig.diagnostics

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.BuildConfig
import io.wolftown.lauschig.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The preview of what would be sent: the head and the newest [PREVIEW_ENTRIES] entries. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val report: DiagnosticReport) : ViewModel() {
    private val _preview = MutableStateFlow<String?>(null)
    val preview: StateFlow<String?> = _preview.asStateFlow()

    fun loadPreview() {
        viewModelScope.launch { _preview.value = previewOf(report.text()) }
    }

    /** Writes the file; [onReady] gets it (null: it could not be written). */
    fun prepare(onReady: (File?) -> Unit) {
        viewModelScope.launch {
            val file = try {
                report.write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "diagnostic log not written", e)
                null
            }
            onReady(file)
        }
    }

    companion object {
        private const val TAG = "Diagnostics"
        const val PREVIEW_ENTRIES = 40

        /** The head (up to the separator) and the newest entries; the file has them all. */
        fun previewOf(text: String): String {
            val head = text.substringBefore("----\n") + "----\n"
            val body = text.substringAfter("----\n", "")
            val entries = body.split(Regex("\n(?=\\d{4}-\\d{2}-\\d{2} )")).filter { it.isNotBlank() }
            val shown = entries.takeLast(PREVIEW_ENTRIES)
            val skipped = entries.size - shown.size
            return head + (if (skipped > 0) "… $skipped\n" else "") + shown.joinToString("\n").trimEnd()
        }
    }
}

@Composable
fun DiagnosticsSection(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val subject = stringResource(R.string.diagnostics_subject, BuildConfig.VERSION_NAME)
    val chooser = stringResource(R.string.diagnostics_chooser)
    val failed = stringResource(R.string.diagnostics_failed)
    DiagnosticsRow(
        preview = preview,
        onOpen = viewModel::loadPreview,
        onShare = {
            viewModel.prepare { file ->
                if (file == null) {
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        context.startActivity(DiagnosticReport.shareIntent(context, file, subject, chooser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
    )
}

/**
 * "Diagnose-Log teilen" in "Über Lauschig": a tap shows what the log holds (and what never), with a preview; "Teilen"
 * opens the share sheet.
 */
@Composable
fun DiagnosticsRow(preview: String?, onOpen: () -> Unit, onShare: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.diagnostics_title)) },
        supportingContent = { Text(stringResource(R.string.diagnostics_hint)) },
        leadingContent = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable { open = true; onOpen() }.testTag("shareDiagnostics"),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.diagnostics_explain), style = MaterialTheme.typography.bodyMedium)
                    if (preview != null) {
                        Box(
                            Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(max = 240.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                                .verticalScroll(rememberScrollState(), reverseScrolling = true)
                                .padding(8.dp)
                                .testTag("diagnosticsPreview"),
                        ) {
                            Text(preview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false; onShare() }, modifier = Modifier.testTag("diagnosticsShare")) {
                    Text(stringResource(R.string.diagnostics_share))
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.diagnostics_cancel)) } },
        )
    }
}
