package io.wolftown.lauschig.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.download.DownloadSettings
import io.wolftown.lauschig.download.DownloadVolume
import io.wolftown.lauschig.download.StorageVolumes
import io.wolftown.lauschig.download.VolumeKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One choice of the download location. [path] null = the internal storage. [usable] false: the chosen card is not there
 * (or only readable); it is listed so the choice stays visible, but cannot be picked.
 */
@Immutable
data class LocationOption(val path: String?, val kind: VolumeKind, val label: String?, val freeBytes: Long?, val usable: Boolean = true)

@Immutable
data class DownloadLocationUi(
    val loaded: Boolean = false,
    /** The internal storage first, then every writable other volume, then the chosen one if it is not usable now. */
    val options: List<LocationOption> = emptyList(),
    /** The stored choice ([DownloadSettings.storagePath]); null = internal. */
    val chosen: String? = null,
) {
    /** The chosen volume is not there now: new downloads go to the internal storage. */
    val chosenMissing: Boolean get() = chosen != null && options.none { it.path == chosen && it.usable }

    /** Where new downloads go right now. */
    val active: LocationOption? get() = options.firstOrNull { it.usable && it.path == chosen } ?: options.firstOrNull { it.path == null }

    /** The dialog is offered with a real choice, or to give up a card that is gone. */
    val canChoose: Boolean get() = options.count { it.usable } > 1 || chosenMissing

    companion object {
        fun of(volumes: List<DownloadVolume>, free: (DownloadVolume) -> Long?, chosen: String?): DownloadLocationUi {
            val options = volumes.filter { it.kind == VolumeKind.INTERNAL || it.writable }.map { v ->
                LocationOption(if (v.kind == VolumeKind.INTERNAL) null else v.path.absolutePath, v.kind, v.label, free(v))
            }
            val missing = chosen != null && options.none { it.path == chosen }
            val readOnly = volumes.firstOrNull { it.path.absolutePath == chosen && !it.writable }
            val extra = if (missing) listOf(LocationOption(chosen, readOnly?.kind ?: VolumeKind.SD_CARD, readOnly?.label, null, usable = false)) else emptyList()
            return DownloadLocationUi(loaded = true, options = options + extra, chosen = chosen)
        }
    }
}

/** The volumes and their free space, read anew every [refreshMs] and after every choice. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadLocationViewModel(
    private val settings: DownloadSettings,
    private val volumes: StorageVolumes,
    io: CoroutineDispatcher,
    refreshMs: Long = 30_000,
) : ViewModel() {
    @Inject constructor(settings: DownloadSettings, volumes: StorageVolumes) : this(settings, volumes, Dispatchers.IO)

    private val reads = MutableStateFlow(0)

    private val read = reads.flatMapLatest {
        flow {
            while (true) {
                emit(volumes.all().map { v -> v to runCatching { v.path.usableSpace }.getOrNull() })
                delay(refreshMs)
            }
        }.flowOn(io)
    }

    val state: StateFlow<DownloadLocationUi> = combine(read, settings.storagePath) { list, chosen ->
        val free = list.associate { (v, bytes) -> v.path.absolutePath to bytes }
        DownloadLocationUi.of(list.map { it.first }, { free[it.path.absolutePath] }, chosen)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadLocationUi())

    fun choose(path: String?) {
        viewModelScope.launch {
            settings.setStoragePath(path)
            volumes.refresh()
            reads.update { it + 1 }
        }
    }
}

@Composable
fun DownloadLocationSection(viewModel: DownloadLocationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadLocationRow(state, viewModel::choose)
}

@Composable
private fun optionName(o: LocationOption): String = o.label ?: stringResource(
    when (o.kind) {
        VolumeKind.INTERNAL -> R.string.settings_storage_internal
        VolumeKind.SD_CARD -> R.string.settings_storage_sd
        VolumeKind.EXTERNAL -> R.string.settings_storage_external
    },
)

@Composable
private fun optionDetail(o: LocationOption): String? {
    if (!o.usable) return stringResource(R.string.settings_storage_unavailable)
    val free = o.freeBytes ?: return null
    // "2.0 GB" never breaks between the number and its unit.
    return stringResource(R.string.settings_storage_free, Formatter.formatShortFileSize(LocalContext.current, free).replace(' ', '\u00A0'))
}

/**
 * "Speicherort": where new downloads go and how much is free there. With more than one volume (or a chosen card that is
 * gone) a tap opens the choice; existing downloads stay where they are.
 */
@Composable
fun DownloadLocationRow(state: DownloadLocationUi, onChoose: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (!state.loaded) return
    var open by rememberSaveable { mutableStateOf(false) }
    val active = state.active
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_storage)) },
        supportingContent = {
            Column {
                if (active != null) Text(listOfNotNull(optionName(active), optionDetail(active)).joinToString(" · "))
                if (state.chosenMissing) Text(stringResource(R.string.settings_storage_missing), color = MaterialTheme.colorScheme.error)
            }
        },
        modifier = modifier.fillMaxWidth().then(if (state.canChoose) Modifier.clickable { open = true } else Modifier).testTag("downloadLocation"),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_storage_choose)) },
            text = {
                Column {
                    Column(Modifier.selectableGroup()) {
                        state.options.forEach { o ->
                            val selected = o.path == state.chosen
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(selected = selected, enabled = o.usable, role = Role.RadioButton) { open = false; if (!selected) onChoose(o.path) }
                                    .padding(vertical = 8.dp)
                                    .testTag("location:${o.path ?: "internal"}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null, enabled = o.usable)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(optionName(o), style = MaterialTheme.typography.bodyLarge)
                                    optionDetail(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.settings_storage_hint),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_storage_cancel)) } },
        )
    }
}
