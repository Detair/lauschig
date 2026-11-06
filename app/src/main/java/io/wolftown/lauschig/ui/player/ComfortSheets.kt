package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimerMachine
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.settings.PlaybackSettings
import io.wolftown.lauschig.ui.detail.formatClock

/** Countdown, rounded up so it never shows 00:00 while still playing. */
fun formatCountdown(ms: Long): String = formatClock(((ms.coerceAtLeast(0) + 999) / 1000).toDouble())

@Composable
fun sleepDescription(sleep: SleepTimerUi): String = stringResource(
    if (sleep.mode == SleepMode.EndOfChapter) R.string.player_sleep_chapter_active else R.string.player_sleep_active,
    formatCountdown(sleep.remainingMs),
)

@Composable
fun boostLabel(db: Int): String = if (db <= 0) stringResource(R.string.audio_boost_off) else stringResource(R.string.audio_boost_db, db)

/** The Klang tile's value: the boost if one applies, else the night compressor, else skipping silence, else "off". */
@Composable
fun soundValue(state: ComfortUiState): String {
    val boost = state.titleBoostDb ?: state.defaultBoostDb
    return when {
        state.casting -> stringResource(R.string.player_tile_off)
        boost > 0 -> boostLabel(boost)
        state.compressor -> stringResource(R.string.player_tile_night)
        state.skipSilence -> stringResource(R.string.player_tile_no_silence)
        else -> stringResource(R.string.player_tile_off)
    }
}

/** Everything the Klang tile has on, for TalkBack ("Klang: +3 dB, Sprachfokus & Nacht-Kompression"). */
@Composable
fun soundDescription(state: ComfortUiState): String {
    if (state.casting) return stringResource(R.string.cast_sound_unavailable_description)
    val boost = state.titleBoostDb ?: state.defaultBoostDb
    val parts = listOfNotNull(
        boostLabel(boost).takeIf { boost > 0 },
        stringResource(R.string.audio_compressor).takeIf { state.compressor },
        stringResource(R.string.audio_skip_silence).takeIf { state.skipSilence },
    )
    return stringResource(R.string.player_sound_description, parts.joinToString(", ").ifEmpty { stringResource(R.string.player_tile_off) })
}

/** The Sleep and Klang tiles of the full player, with their sheets. The Sleep tile shows the time left, warmly lit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowScope.ComfortButtons(
    state: ComfortUiState,
    onStartSleep: (SleepMode) -> Unit,
    onCancelSleep: () -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onBoost: (Int?) -> Unit,
    onSleepChime: (Boolean) -> Unit = {},
    onCompressor: (Boolean) -> Unit = {},
) {
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showAudio by rememberSaveable { mutableStateOf(false) }
    val sleep = state.sleep
    val sleepLabel = if (sleep != null) sleepDescription(sleep) else stringResource(R.string.player_sleep_off_description)
    PlayerTile(
        Icons.Rounded.Bedtime,
        stringResource(R.string.player_tile_sleep),
        if (sleep != null) formatCountdown(sleep.remainingMs) else stringResource(R.string.player_tile_off),
        onClick = { showSleep = true },
        active = sleep != null,
        warm = true,
        modifier = Modifier.testTag("sleepButton").semantics { contentDescription = sleepLabel },
    )
    val boost = state.titleBoostDb ?: state.defaultBoostDb
    val soundLabel = soundDescription(state)
    PlayerTile(
        Icons.Rounded.GraphicEq,
        stringResource(R.string.player_tile_sound),
        soundValue(state),
        onClick = { showAudio = true },
        active = !state.casting && (state.skipSilence || state.compressor || boost > 0),
        modifier = Modifier.testTag("audioButton").semantics { contentDescription = soundLabel },
    )
    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            SleepSheet(
                state.sleep, state.sleepChime, onStart = { onStartSleep(it); showSleep = false }, onCancel = { onCancelSleep(); showSleep = false },
                onChime = onSleepChime,
            )
        }
    }
    if (showAudio) {
        ModalBottomSheet(onDismissRequest = { showAudio = false }) { AudioSheet(state, onSkipSilence, onBoost, onCompressor) }
    }
}

/** "Shake to extend" while the sleep timer is in its last minute. */
@Composable
fun ShakeHint(state: ComfortUiState, modifier: Modifier = Modifier) {
    if (state.sleep?.shakeToExtend != true) return
    Text(
        stringResource(R.string.sleep_shake_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp).testTag("shakeHint"),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SleepSheet(sleep: SleepTimerUi?, chime: Boolean, onStart: (SleepMode) -> Unit, onCancel: () -> Unit, onChime: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("sleepSheet")) {
        Text(stringResource(R.string.player_sleep_timer), style = MaterialTheme.typography.titleMedium)
        if (sleep != null) {
            Text(sleepDescription(sleep), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            stringResource(R.string.sleep_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTimerMachine.DURATIONS_MIN.forEach { min ->
                FilterChip(
                    selected = (sleep?.mode as? SleepMode.Minutes)?.minutes == min,
                    onClick = { onStart(SleepMode.Minutes(min)) },
                    label = { Text(stringResource(R.string.sleep_minutes, min)) },
                    modifier = Modifier.testTag("sleep:$min"),
                )
            }
            FilterChip(
                selected = sleep?.mode == SleepMode.EndOfChapter,
                onClick = { onStart(SleepMode.EndOfChapter) },
                label = { Text(stringResource(R.string.sleep_end_of_chapter)) },
                modifier = Modifier.testTag("sleep:chapter"),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_sleep_chime)) },
            supportingContent = { Text(stringResource(R.string.settings_sleep_chime_hint)) },
            trailingContent = { Switch(checked = chime, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectable(selected = chime, role = Role.Switch) { onChime(!chime) }
                .testTag("sleepChimeSwitch"),
        )
        if (sleep != null) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp).testTag("sleepCancel")) { Text(stringResource(R.string.sleep_cancel)) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AudioSheet(state: ComfortUiState, onSkipSilence: (Boolean) -> Unit, onBoost: (Int?) -> Unit, onCompressor: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("audioSheet")) {
        Text(stringResource(R.string.player_tile_sound), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        // The settings stay what they are, for when the sound is back here; nothing pretends to reach the receiver.
        val enabled = !state.casting
        if (state.casting) {
            Text(
                stringResource(R.string.cast_effects_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).testTag("soundUnavailable"),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_compressor)) },
            supportingContent = { Text(stringResource(R.string.audio_compressor_hint)) },
            trailingContent = { Switch(checked = state.compressor && enabled, onCheckedChange = null, enabled = enabled) },
            modifier = Modifier.fillMaxWidth().selectable(selected = state.compressor, role = Role.Switch, enabled = enabled) { onCompressor(!state.compressor) }
                .testTag("compressor"),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_skip_silence)) },
            supportingContent = { Text(stringResource(R.string.audio_skip_silence_hint)) },
            trailingContent = { Switch(checked = state.skipSilence && enabled, onCheckedChange = null, enabled = enabled) },
            modifier = Modifier.fillMaxWidth().selectable(selected = state.skipSilence, role = Role.Switch, enabled = enabled) { onSkipSilence(!state.skipSilence) }
                .testTag("skipSilence"),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_boost)) },
            supportingContent = { Text(stringResource(R.string.audio_boost_hint)) },
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = enabled && state.titleBoostDb == null,
                enabled = enabled,
                onClick = { onBoost(null) },
                label = { Text(stringResource(R.string.audio_boost_default, boostLabel(state.defaultBoostDb))) },
                modifier = Modifier.testTag("boost:default"),
            )
            PlaybackSettings.BOOST_CHOICES.forEach { db ->
                FilterChip(
                    selected = enabled && state.titleBoostDb == db,
                    enabled = enabled,
                    onClick = { onBoost(db) },
                    label = { Text(boostLabel(db)) },
                    modifier = Modifier.testTag("boost:$db"),
                )
            }
        }
    }
}
