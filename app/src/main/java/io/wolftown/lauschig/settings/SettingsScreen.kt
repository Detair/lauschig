package io.wolftown.lauschig.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.wolftown.lauschig.download.MeteredDownload
import io.wolftown.lauschig.external.AudibleRegions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@Immutable
data class SettingsUiState(
    val nextVolume: NextVolumeMode = NextVolumeMode.SUGGEST,
    val wifiOnly: Boolean = true,
    val cleanupReminder: Boolean = true,
    val missingVolumes: Boolean = true,
    val audibleRegion: String = AudibleRegions.DEFAULT,
    val autoDownload: AutoDownloadMode = AutoDownloadMode.OFF,
    val downloadOnPlay: Boolean = true,
    val meteredOnPlay: MeteredDownload = MeteredDownload.ASK,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: AppSettings,
    private val download: DownloadSettings,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        combine(app.nextVolumeMode, download.wifiOnly, app.cleanupReminder, app.missingVolumesEnabled, app.audibleRegion) { next, wifi, reminder, missing, region ->
            SettingsUiState(next, wifi, reminder, missing, region)
        },
        app.autoDownload,
        download.downloadOnPlay,
        download.meteredOnPlay,
    ) { base, auto, onPlay, metered -> base.copy(autoDownload = auto, downloadOnPlay = onPlay, meteredOnPlay = metered) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNextVolume(mode: NextVolumeMode) { viewModelScope.launch { app.setNextVolumeMode(mode) } }
    fun setWifiOnly(on: Boolean) { viewModelScope.launch { download.setWifiOnly(on) } }
    fun setCleanupReminder(on: Boolean) { viewModelScope.launch { app.setCleanupReminder(on) } }
    fun setMissingVolumes(on: Boolean) { viewModelScope.launch { app.setMissingVolumesEnabled(on) } }
    fun setAudibleRegion(code: String) { viewModelScope.launch { app.setAudibleRegion(code) } }
    fun setAutoDownload(mode: AutoDownloadMode) { viewModelScope.launch { app.setAutoDownload(mode) } }
    fun setDownloadOnPlay(on: Boolean) { viewModelScope.launch { download.setDownloadOnPlay(on) } }
    fun setMeteredOnPlay(mode: MeteredDownload) { viewModelScope.launch { download.setMeteredOnPlay(mode) } }
}

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenCleanup: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, onBack, onOpenCleanup, viewModel::setNextVolume, viewModel::setWifiOnly, viewModel::setCleanupReminder,
        viewModel::setMissingVolumes, viewModel::setAudibleRegion, viewModel::setAutoDownload,
        onDownloadOnPlay = viewModel::setDownloadOnPlay, onMeteredOnPlay = viewModel::setMeteredOnPlay,
        rmabSection = { io.wolftown.lauschig.rmab.RmabSettingsSection() },
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenCleanup: () -> Unit,
    onNextVolume: (NextVolumeMode) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onCleanupReminder: (Boolean) -> Unit,
    onMissingVolumes: (Boolean) -> Unit = {},
    onAudibleRegion: (String) -> Unit = {},
    onAutoDownload: (AutoDownloadMode) -> Unit = {},
    onDownloadOnPlay: (Boolean) -> Unit = {},
    onMeteredOnPlay: (MeteredDownload) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Slot for the ReadMeABook section (needs Hilt, so the route supplies it). */
    rmabSection: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settingsScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }
        Section(R.string.settings_section_series)
        ModeRow(NextVolumeMode.SUGGEST, state.nextVolume, R.string.settings_next_suggest, R.string.settings_next_suggest_hint, onNextVolume)
        ModeRow(NextVolumeMode.PREFETCH, state.nextVolume, R.string.settings_next_prefetch, R.string.settings_next_prefetch_hint, onNextVolume)
        SwitchRow(R.string.settings_missing_volumes, R.string.settings_missing_volumes_hint, state.missingVolumes, onMissingVolumes, Modifier.testTag("missingVolumesSwitch"))
        RegionRow(state.audibleRegion, state.missingVolumes, onAudibleRegion)

        rmabSection()

        Section(R.string.settings_section_downloads)
        SwitchRow(R.string.settings_wifi_only, R.string.settings_wifi_only_hint, state.wifiOnly, onWifiOnly)
        SwitchRow(R.string.settings_download_on_play, R.string.settings_download_on_play_hint, state.downloadOnPlay, onDownloadOnPlay, Modifier.testTag("downloadOnPlay"))
        MeteredRow(state.meteredOnPlay, state.downloadOnPlay, onMeteredOnPlay)
        AutoDownloadRow(state.autoDownload, onAutoDownload)
        SwitchRow(R.string.settings_cleanup_reminder, R.string.settings_cleanup_reminder_hint, state.cleanupReminder, onCleanupReminder)
        ListItem(
            headlineContent = { Text(stringResource(R.string.cleanup_title)) },
            supportingContent = { Text(stringResource(R.string.settings_cleanup_open_hint)) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenCleanup).testTag("openCleanup"),
        )
    }
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

@Composable
private fun ModeRow(mode: NextVolumeMode, current: NextVolumeMode, title: Int, hint: Int, onSelect: (NextVolumeMode) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        leadingContent = { RadioButton(selected = mode == current, onClick = null) },
        modifier = Modifier.fillMaxWidth().selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }.testTag("mode:$mode"),
    )
}

@Composable
private fun SwitchRow(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = modifier.fillMaxWidth().selectable(selected = checked, role = Role.Switch) { onChange(!checked) },
    )
}

/** Region names come from the system locale data; Audible's `uk` is the country code `GB`. */
private fun regionName(code: String): String =
    Locale.Builder().setRegion(if (code == "uk") "GB" else code.uppercase()).build().displayCountry.ifEmpty { code }

@Composable
private fun RegionRow(current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_audible_region)) },
            supportingContent = { Text(stringResource(R.string.settings_audible_region_hint)) },
            trailingContent = { Text(regionName(current)) },
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.testTag("audibleRegion"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AudibleRegions.all.sorted().forEach { code ->
                DropdownMenuItem(text = { Text(regionName(code)) }, onClick = { open = false; onSelect(code) }, modifier = Modifier.testTag("region:$code"))
            }
        }
    }
}

@Composable
private fun autoDownloadLabel(mode: AutoDownloadMode): String =
    if (mode == AutoDownloadMode.OFF) stringResource(R.string.settings_auto_download_off)
    else stringResource(R.string.settings_auto_download_newest, mode.count)

@Composable
private fun AutoDownloadRow(current: AutoDownloadMode, onSelect: (AutoDownloadMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_download)) },
            supportingContent = { Text(stringResource(R.string.settings_auto_download_hint)) },
            trailingContent = { Text(autoDownloadLabel(current)) },
            modifier = Modifier.fillMaxWidth().clickable { open = true }.testTag("autoDownload"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            AutoDownloadMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(autoDownloadLabel(mode)) }, onClick = { open = false; onSelect(mode) }, modifier = Modifier.testTag("autoDownload:$mode"))
            }
        }
    }
}

@Composable
private fun meteredLabel(mode: MeteredDownload): String = stringResource(
    when (mode) {
        MeteredDownload.ASK -> R.string.settings_metered_ask
        MeteredDownload.ALWAYS -> R.string.settings_metered_always
        MeteredDownload.WIFI -> R.string.settings_metered_wifi
    },
)

@Composable
private fun MeteredRow(current: MeteredDownload, enabled: Boolean, onSelect: (MeteredDownload) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_metered_on_play)) },
            supportingContent = { Text(stringResource(R.string.settings_metered_on_play_hint)) },
            trailingContent = { Text(meteredLabel(current)) },
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.testTag("meteredOnPlay"),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            MeteredDownload.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(meteredLabel(mode)) }, onClick = { open = false; onSelect(mode) }, modifier = Modifier.testTag("meteredOnPlay:$mode"))
            }
        }
    }
}
