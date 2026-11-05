package io.wolftown.lauschig.cleanup

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.account.AccountStore
import io.wolftown.lauschig.db.dao.AudioFileDao
import io.wolftown.lauschig.db.dao.DownloadDao
import io.wolftown.lauschig.download.DownloadManager
import io.wolftown.lauschig.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class CleanupRow(val itemId: String, val title: String, val bytes: Long, val ignored: Boolean)

@Immutable
data class CleanupUiState(val rows: List<CleanupRow> = emptyList(), val loaded: Boolean = false)

/**
 * Finished titles and episodes that are still downloaded; kept ("Behalten") ones are listed too, so the choice can be
 * undone. "Behalten" also keeps a title from the automatic clean-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CleanupViewModel @Inject constructor(
    accounts: AccountStore,
    downloadDao: DownloadDao,
    audioFiles: AudioFileDao,
    private val settings: AppSettings,
    private val downloads: DownloadManager,
) : ViewModel() {
    private var serverId: String? = null

    val state: StateFlow<CleanupUiState> = accounts.account.flatMapLatest { account ->
        val sid = account?.serverId ?: return@flatMapLatest flowOf(CleanupUiState(loaded = true))
        serverId = sid
        combine(downloadDao.observeFinishedEntries(sid), audioFiles.observeLocalBytes(sid), settings.cleanupIgnored) { list, bytes, ignored ->
            val byId = bytes.associate { it.itemId to it.bytes }
            CleanupUiState(list.map { CleanupRow(it.itemKey, it.title, byId[it.itemKey] ?: 0L, "$sid:${it.itemKey}" in ignored) }, loaded = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupUiState())

    fun setIgnored(itemId: String, ignored: Boolean) {
        val sid = serverId ?: return
        viewModelScope.launch { settings.setCleanupIgnored("$sid:$itemId", ignored) }
    }

    /** Deletes the downloads (files and rows); progress stays. */
    fun delete(itemIds: Collection<String>) {
        val sid = serverId ?: return
        viewModelScope.launch { itemIds.forEach { downloads.cancel(sid, it, deleteFiles = true) } }
    }
}

@Composable
fun CleanupRoute(onBack: () -> Unit, viewModel: CleanupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CleanupScreen(state, onBack, viewModel::setIgnored, viewModel::delete)
}

/**
 * One row per title: "delete" checkbox (preselected unless the title is ignored) and "don't remind me again".
 * The button deletes the selected downloads.
 */
@Composable
fun CleanupScreen(
    state: CleanupUiState,
    onBack: () -> Unit,
    onIgnore: (itemId: String, ignored: Boolean) -> Unit,
    onDelete: (Collection<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ids the user deselected; everything else that is not ignored counts as selected.
    var deselected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selected = state.rows.filter { !it.ignored && it.itemId !in deselected }
    val context = LocalContext.current
    Column(modifier.fillMaxSize().testTag("cleanupScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.cleanup_title), style = MaterialTheme.typography.titleLarge)
        }
        if (state.loaded && state.rows.isEmpty()) {
            Text(stringResource(R.string.cleanup_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(
            stringResource(R.string.cleanup_hint),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f)) {
            items(state.rows, key = { it.itemId }) { row ->
                val isSelected = !row.ignored && row.itemId !in deselected
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isSelected,
                            enabled = !row.ignored,
                            onCheckedChange = { on -> deselected = if (on) deselected - row.itemId else deselected + row.itemId },
                            modifier = Modifier.testTag("delete:${row.itemId}"),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(Formatter.formatShortFileSize(context, row.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = row.ignored, onCheckedChange = { onIgnore(row.itemId, it) }, modifier = Modifier.testTag("ignore:${row.itemId}"))
                        Text(stringResource(R.string.cleanup_dont_remind), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
        val total = selected.sumOf { it.bytes }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDelete(selected.map { it.itemId }) }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("cleanupDelete")) {
                Text(stringResource(R.string.cleanup_delete_selected, selected.size, Formatter.formatShortFileSize(context, total)))
            }
        }
    }
}
