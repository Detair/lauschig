package io.wolftown.lauschig.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.KeptAccount
import io.wolftown.lauschig.account.KeptAccountDeletion
import io.wolftown.lauschig.account.KeptDownloadSizes
import io.wolftown.lauschig.account.KeptDownloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Downloads page's "Downloads früherer Konten": every kept account but the signed-in one, also after its one-time
 * question was answered with "Behalten" – keeping is never final, the storage can always be given back.
 */
@HiltViewModel
class KeptDownloadsViewModel(
    sizes: Flow<List<KeptDownloads>>,
    private val deleteAccount: suspend (KeptAccount) -> Boolean,
) : ViewModel() {
    @Inject constructor(sizes: KeptDownloadSizes, deletion: KeptAccountDeletion) : this(sizes.entries, deletion::delete)

    val entries: StateFlow<List<KeptDownloads>> = sizes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Server id being deleted right now. */
    private val _deleting = MutableStateFlow<String?>(null)
    val deleting: StateFlow<String?> = _deleting.asStateFlow()

    /** Server id whose last delete failed (its entry stays). */
    private val _failed = MutableStateFlow<String?>(null)
    val failed: StateFlow<String?> = _failed.asStateFlow()

    fun delete(account: KeptAccount) {
        if (_deleting.value != null) return
        _deleting.value = account.serverId
        _failed.value = null
        viewModelScope.launch {
            if (!deleteAccount(account)) _failed.value = account.serverId
            _deleting.value = null
        }
    }
}

@Composable
fun KeptDownloadsSection(viewModel: KeptDownloadsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val deleting by viewModel.deleting.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    KeptDownloadsContent(entries, deleting, failed, viewModel::delete)
}

@Composable
fun KeptDownloadsContent(entries: List<KeptDownloads>, deleting: String?, failed: String?, onDelete: (KeptAccount) -> Unit) {
    if (entries.isEmpty()) return
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes).replace(' ', ' ')
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.testTag("keptDownloads")) {
        Text(
            stringResource(R.string.downloads_previous_accounts),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        entries.forEach { e ->
            val who = stringResource(R.string.downloads_previous_entry, e.account.username, e.account.host)
            ListItem(
                headlineContent = { Text(who) },
                supportingContent = {
                    Column {
                        Text(if (e.bytes > 0) size(e.bytes) else stringResource(R.string.downloads_previous_data))
                        if (failed == e.account.serverId) Text(stringResource(R.string.kept_delete_failed), color = MaterialTheme.colorScheme.error)
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = { confirm = e.account.serverId },
                        enabled = deleting == null,
                        modifier = Modifier.testTag("deleteKept:${e.account.serverId}"),
                    ) { Icon(Icons.Rounded.Delete, stringResource(R.string.downloads_previous_delete, who)) }
                },
                modifier = Modifier.testTag("kept:${e.account.serverId}"),
            )
        }
    }
    entries.firstOrNull { it.account.serverId == confirm }?.let { e ->
        val who = stringResource(R.string.downloads_previous_entry, e.account.username, e.account.host)
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.downloads_previous_confirm_title, who)) },
            text = {
                Text(
                    if (e.bytes > 0) stringResource(R.string.downloads_previous_confirm_message, size(e.bytes))
                    else stringResource(R.string.downloads_previous_confirm_message_data),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirm = null; onDelete(e.account) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirmDeleteKept"),
                ) { Text(stringResource(R.string.previous_account_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.sign_out_cancel)) } },
        )
    }
}
