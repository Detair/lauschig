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
    val playback: PlaybackPrefs = PlaybackPrefs(),
    val appearance: Appearance = Appearance(),
)

@Immutable
data class PlaybackPrefs(
    val rewindSec: Int = PlaybackSettings.DEFAULT_REWIND,
    val defaultSpeed: Float = 1f,
    val skipSilence: Boolean = false,
    val defaultBoostDb: Int = 0,
    val autoResume: Boolean = false,
    val continueOnStart: Boolean = false,
)

/** Callbacks of the playback section. */
class PlaybackPrefsActions(
    val onRewind: (Int) -> Unit = {},
    val onDefaultSpeed: (Float) -> Unit = {},
    val onSkipSilence: (Boolean) -> Unit = {},
    val onDefaultBoost: (Int) -> Unit = {},
    val onAutoResume: (Boolean) -> Unit = {},
    val onContinueOnStart: (Boolean) -> Unit = {},
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val app: AppSettings,
    private val download: DownloadSettings,
    private val playbackSettings: PlaybackSettings,
    private val ui: UiSettings,
) : ViewModel() {
    private val playback = combine(
        combine(playbackSettings.rewindOnResumeSec, playbackSettings.defaultSpeed, playbackSettings.skipSilence) { r, sp, sk -> Triple(r, sp, sk) },
        playbackSettings.defaultBoostDb,
        playbackSettings.autoResumeOnConnect,
        playbackSettings.continueOnStart,
    ) { (r, sp, sk), boost, auto, cont -> PlaybackPrefs(r, sp, sk, boost, auto, cont) }

    val state: StateFlow<SettingsUiState> = combine(
        combine(app.nextVolumeMode, download.wifiOnly, app.cleanupReminder, app.missingVolumesEnabled, app.audibleRegion) { next, wifi, reminder, missing, region ->
            SettingsUiState(next, wifi, reminder, missing, region)
        },
        app.autoDownload,
        download.downloadOnPlay,
        download.meteredOnPlay,
        playback,
    ) { base, auto, onPlay, metered, pb -> base.copy(autoDownload = auto, downloadOnPlay = onPlay, meteredOnPlay = metered, playback = pb) }
        .combine(ui.appearance) { s, look -> s.copy(appearance = look) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNextVolume(mode: NextVolumeMode) { viewModelScope.launch { app.setNextVolumeMode(mode) } }
    fun setWifiOnly(on: Boolean) { viewModelScope.launch { download.setWifiOnly(on) } }
    fun setCleanupReminder(on: Boolean) { viewModelScope.launch { app.setCleanupReminder(on) } }
    fun setMissingVolumes(on: Boolean) { viewModelScope.launch { app.setMissingVolumesEnabled(on) } }
    fun setAudibleRegion(code: String) { viewModelScope.launch { app.setAudibleRegion(code) } }
    fun setAutoDownload(mode: AutoDownloadMode) { viewModelScope.launch { app.setAutoDownload(mode) } }
    fun setDownloadOnPlay(on: Boolean) { viewModelScope.launch { download.setDownloadOnPlay(on) } }
    fun setMeteredOnPlay(mode: MeteredDownload) { viewModelScope.launch { download.setMeteredOnPlay(mode) } }

    val appearanceActions = AppearanceActions(
        onTheme = { viewModelScope.launch { ui.setTheme(it) } },
        onPureBlack = { viewModelScope.launch { ui.setPureBlack(it) } },
        onDynamicColor = { viewModelScope.launch { ui.setDynamicColor(it) } },
        onFontScale = { viewModelScope.launch { ui.setFontScale(it) } },
    )

    val playbackActions = PlaybackPrefsActions(
        onRewind = { viewModelScope.launch { playbackSettings.setRewindOnResumeSec(it) } },
        onDefaultSpeed = { viewModelScope.launch { playbackSettings.setDefaultSpeed(it) } },
        onSkipSilence = { viewModelScope.launch { playbackSettings.setSkipSilence(it) } },
        onDefaultBoost = { viewModelScope.launch { playbackSettings.setDefaultBoostDb(it) } },
        onAutoResume = { viewModelScope.launch { playbackSettings.setAutoResumeOnConnect(it) } },
        onContinueOnStart = { viewModelScope.launch { playbackSettings.setContinueOnStart(it) } },
    )
}

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    onOpenNavigation: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, onBack, onOpenCleanup, viewModel::setNextVolume, viewModel::setWifiOnly, viewModel::setCleanupReminder,
        viewModel::setMissingVolumes, viewModel::setAudibleRegion, viewModel::setAutoDownload,
        onDownloadOnPlay = viewModel::setDownloadOnPlay, onMeteredOnPlay = viewModel::setMeteredOnPlay,
        playbackActions = viewModel.playbackActions,
        appearanceActions = viewModel.appearanceActions,
        onOpenNavigation = onOpenNavigation,
        rmabSection = { io.wolftown.lauschig.rmab.RmabSettingsSection() },
        onOpenDownloads = onOpenDownloads,
        localSection = { io.wolftown.lauschig.local.LocalFoldersSection() },
        onOpenConnection = onOpenConnection,
        linksSection = { io.wolftown.lauschig.links.LinksSection() },
        adminSection = { io.wolftown.lauschig.admin.AdminSettingsEntry(onOpenAdmin) },
        accountSection = { AccountSection() },
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
    playbackActions: PlaybackPrefsActions = PlaybackPrefsActions(),
    modifier: Modifier = Modifier,
    appearanceActions: AppearanceActions = AppearanceActions(),
    onOpenNavigation: () -> Unit = {},
    /** Slot for the ReadMeABook section (needs Hilt, so the route supplies it). */
    rmabSection: @Composable () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    /** Slot for the "on this device" folders (needs Hilt and the folder picker). */
    localSection: @Composable () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    /** Slot for the links hint (needs the system's link settings). */
    linksSection: @Composable () -> Unit = {},
    /** Slot for "Server-Verwaltung" (admins only; needs Hilt). */
    adminSection: @Composable () -> Unit = {},
    /** Slot for "Konto" with "Abmelden" (needs Hilt); the first section. */
    accountSection: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settingsScreen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
        }
        accountSection()
        PlaybackSection(state.playback, playbackActions)
        AppearanceSection(state.appearance, appearanceActions) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_settings_entry)) },
                supportingContent = { Text(stringResource(R.string.nav_settings_entry_hint)) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenNavigation).testTag("openNavigation"),
            )
        }

        Section(R.string.settings_section_series)
        ModeRow(NextVolumeMode.SUGGEST, state.nextVolume, R.string.settings_next_suggest, R.string.settings_next_suggest_hint, onNextVolume)
        ModeRow(NextVolumeMode.PREFETCH, state.nextVolume, R.string.settings_next_prefetch, R.string.settings_next_prefetch_hint, onNextVolume)
        SwitchRow(R.string.settings_missing_volumes, R.string.settings_missing_volumes_hint, state.missingVolumes, onMissingVolumes, Modifier.testTag("missingVolumesSwitch"))
        RegionRow(state.audibleRegion, state.missingVolumes, onAudibleRegion)

        rmabSection()

        Section(R.string.settings_section_downloads)
        ListItem(
            headlineContent = { Text(stringResource(R.string.downloads_manage)) },
            supportingContent = { Text(stringResource(R.string.downloads_manage_hint)) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDownloads).testTag("openDownloads"),
        )
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

        Section(R.string.settings_section_local)
        localSection()

        Section(R.string.settings_section_connection)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_connection_status)) },
            supportingContent = { Text(stringResource(R.string.settings_connection_status_hint)) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenConnection).testTag("openConnection"),
        )
        linksSection()
        adminSection()

        AboutSection()
    }
}

@Composable
internal fun Section(title: Int) {
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
internal fun SwitchRow(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.fillMaxWidth().selectable(selected = checked, enabled = enabled, role = Role.Switch) { onChange(!checked) },
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

@Composable
private fun PlaybackSection(p: PlaybackPrefs, a: PlaybackPrefsActions) {
    Section(R.string.settings_section_playback)
    ChoiceRow(
        R.string.settings_rewind, R.string.settings_rewind_hint, "rewind", PlaybackSettings.REWIND_CHOICES, p.rewindSec, a.onRewind,
    ) { if (it == 0) stringResource(R.string.settings_rewind_off) else stringResource(R.string.settings_rewind_sec, it) }
    ChoiceRow(
        R.string.settings_default_speed, R.string.settings_default_speed_hint, "defaultSpeed", PlaybackSettings.SPEED_CHOICES, p.defaultSpeed, a.onDefaultSpeed,
    ) { io.wolftown.lauschig.ui.player.formatSpeed(it) }
    SwitchRow(R.string.audio_skip_silence, R.string.audio_skip_silence_hint, p.skipSilence, a.onSkipSilence, Modifier.testTag("settingsSkipSilence"))
    ChoiceRow(
        R.string.settings_default_boost, R.string.settings_default_boost_hint, "defaultBoost", PlaybackSettings.BOOST_CHOICES, p.defaultBoostDb, a.onDefaultBoost,
    ) { io.wolftown.lauschig.ui.player.boostLabel(it) }
    SwitchRow(R.string.settings_auto_resume, R.string.settings_auto_resume_hint, p.autoResume, a.onAutoResume, Modifier.testTag("autoResume"))
    SwitchRow(R.string.settings_continue_on_start, R.string.settings_continue_on_start_hint, p.continueOnStart, a.onContinueOnStart, Modifier.testTag("continueOnStart"))
}

/** A setting with a few values in a dropdown; [label] renders a value. */
@Composable
internal fun <T> ChoiceRow(title: Int, hint: Int, tag: String, choices: List<T>, current: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(hint)) },
            trailingContent = { Text(label(current)) },
            modifier = Modifier.fillMaxWidth().clickable { open = true }.testTag(tag),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            choices.forEach { v ->
                DropdownMenuItem(text = { Text(label(v)) }, onClick = { open = false; onSelect(v) }, modifier = Modifier.testTag("$tag:$v"))
            }
        }
    }
}
