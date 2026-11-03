package io.wolftown.lauschig.local

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.db.dao.ItemDao
import io.wolftown.lauschig.db.dao.LocalFolderWithCount
import io.wolftown.lauschig.db.dao.ProgressDao
import io.wolftown.lauschig.db.entities.LocalFolderEntity
import io.wolftown.lauschig.ui.common.CoverPathCache
import io.wolftown.lauschig.ui.common.CoverTile
import io.wolftown.lauschig.ui.common.ItemSummary
import io.wolftown.lauschig.ui.common.toSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class LocalLibraryUiState(
    val titles: List<ItemSummary> = emptyList(),
    val folders: List<LocalFolderWithCount> = emptyList(),
    val scanning: Boolean = false,
    /** False until the first Room emission, so the empty hint does not flash. */
    val loaded: Boolean = false,
)

/** "Auf dem Gerät": the device library's titles and folders, straight from Room; never touches the network. */
@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val folders: LocalFolders,
    itemDao: ItemDao,
    progressDao: ProgressDao,
    covers: CoverPathCache,
) : ViewModel() {
    private val dev = DeviceLibrary.SERVER_ID

    val state: StateFlow<LocalLibraryUiState> = combine(
        itemDao.observeByLibrary(dev, DeviceLibrary.LIBRARY_ID), progressDao.observeAll(dev), folders.folders, folders.scanning,
    ) { items, progress, fs, scanning ->
        // A rescan may add or replace a cover: the version is part of the key, so the path check runs again.
        covers.sync(items.mapTo(HashSet()) { "${it.id}@${it.coverUpdatedAt}" })
        val byId = progress.associateBy { it.itemId }
        LocalLibraryUiState(
            titles = items.sortedWith(compareBy(NaturalOrder) { it.title }).map { it.toSummary(covers, byId[it.id], downloaded = true) },
            folders = fs,
            scanning = scanning,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalLibraryUiState())

    /** [onResult] (main thread): false if the folder could not be kept. */
    fun add(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(folders.add(uri)) }
    }

    fun remove(uri: String) {
        viewModelScope.launch { folders.remove(uri) }
    }

    fun rescan() = folders.scanNow()
}

/** Opens the system folder picker (`ACTION_OPEN_DOCUMENT_TREE`); no storage permission is needed. */
@Composable
fun rememberFolderPicker(viewModel: LocalLibraryViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.add(uri) { ok -> if (!ok) Toast.makeText(context, R.string.local_add_failed, Toast.LENGTH_SHORT).show() }
    }
    return { launcher.launch(null) }
}

/** Content of the library tab "Auf dem Gerät". */
@Composable
fun DeviceTab(
    onOpenItem: (itemId: String) -> Unit,
    onPlay: (itemId: String) -> Unit,
    onMenu: (ItemSummary) -> Unit,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberFolderPicker(viewModel)
    DeviceTabContent(state, onOpenItem, onPlay, onMenu, onAddFolder = pick, onRescan = viewModel::rescan)
}

@Composable
fun DeviceTabContent(
    state: LocalLibraryUiState,
    onOpenItem: (itemId: String) -> Unit,
    onPlay: (itemId: String) -> Unit,
    onMenu: (ItemSummary) -> Unit,
    onAddFolder: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.loaded) return
    if (state.folders.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp).testTag("deviceEmpty"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.local_empty), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAddFolder, modifier = Modifier.padding(top = 16.dp).testTag("deviceAddFolder")) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                Text(stringResource(R.string.local_add_folder), Modifier.padding(start = 8.dp))
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().testTag("deviceGrid"),
    ) {
        item(key = "device:actions", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onAddFolder, modifier = Modifier.testTag("deviceAddFolder")) { Text(stringResource(R.string.local_add_folder)) }
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.local_scanning), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("deviceScanning"))
                } else {
                    TextButton(onClick = onRescan, modifier = Modifier.testTag("deviceRescan")) { Text(stringResource(R.string.local_rescan)) }
                }
            }
        }
        state.folders.filter { it.error != null }.forEach { f ->
            item(key = "device:error:${f.uri}", span = { GridItemSpan(maxLineSpan) }, contentType = "error") {
                Text(
                    "${f.name}: ${stringResource(folderErrorText(f.error))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.titles.isEmpty() && !state.scanning) {
            item(key = "device:none", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                Text(stringResource(R.string.local_empty_titles), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp).testTag("deviceNoTitles"))
            }
        }
        items(state.titles, key = ItemSummary::key, contentType = { "tile" }) { item ->
            CoverTile(item, onClick = { onOpenItem(item.id) }, onLongClick = { onMenu(item) }, onPlay = { onPlay(item.id) })
        }
    }
}

private fun folderErrorText(error: String?): Int = when (error) {
    LocalFolderEntity.ERROR_NO_ACCESS -> R.string.local_folder_no_access
    LocalFolderEntity.ERROR_MISSING -> R.string.local_folder_missing
    else -> R.string.local_folder_scan_failed
}

/** Settings section "Auf dem Gerät": folders with their state, remove, add, scan now. */
@Composable
fun LocalFoldersSection(viewModel: LocalLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pick = rememberFolderPicker(viewModel)
    LocalFoldersSectionContent(state, onAdd = pick, onRemove = viewModel::remove, onRescan = viewModel::rescan)
}

@Composable
fun LocalFoldersSectionContent(state: LocalLibraryUiState, onAdd: () -> Unit, onRemove: (uri: String) -> Unit, onRescan: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("localFolders")) {
        state.folders.forEach { f ->
            ListItem(
                headlineContent = { Text(f.name) },
                supportingContent = {
                    if (f.error != null) Text(stringResource(folderErrorText(f.error)), color = MaterialTheme.colorScheme.error)
                    else Text(pluralStringResource(R.plurals.local_folder_titles, f.titles, f.titles))
                },
                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { confirm = f.uri }, modifier = Modifier.testTag("removeFolder:${f.name}")) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.local_remove_folder))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.local_add_folder)) },
            supportingContent = { Text(stringResource(R.string.local_add_folder_hint)) },
            leadingContent = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).testTag("addFolder"),
        )
        if (state.folders.isNotEmpty()) {
            ListItem(
                headlineContent = { Text(stringResource(if (state.scanning) R.string.local_scanning else R.string.local_rescan)) },
                supportingContent = { Text(stringResource(R.string.local_rescan_hint)) },
                leadingContent = {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        if (state.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.scanning, onClick = onRescan).testTag("rescanFolders"),
            )
        }
    }
    confirm?.let { uri ->
        val name = state.folders.firstOrNull { it.uri == uri }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.local_remove_title)) },
            text = { Text(stringResource(R.string.local_remove_message, name)) },
            confirmButton = { TextButton(onClick = { confirm = null; onRemove(uri) }, modifier = Modifier.testTag("confirmRemoveFolder")) { Text(stringResource(R.string.local_remove_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.local_remove_dismiss)) } },
        )
    }
}
