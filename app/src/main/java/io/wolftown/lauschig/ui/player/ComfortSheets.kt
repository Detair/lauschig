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

/** Sleep timer and audio buttons of the full player's action area, with their sheets. The timer button shows the countdown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowScope.ComfortButtons(
    state: ComfortUiState,
    onStartSleep: (SleepMode) -> Unit,
    onCancelSleep: () -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onBoost: (Int?) -> Unit,
) {
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showAudio by rememberSaveable { mutableStateOf(false) }
    val sleep = state.sleep
    val sleepLabel = if (sleep != null) sleepDescription(sleep) else stringResource(R.string.player_sleep_timer)
    PlayerAction(
        Icons.Rounded.Bedtime,
        // The short name fits the narrow button; the description keeps the full one.
        if (sleep != null) formatCountdown(sleep.remainingMs) else stringResource(R.string.player_sleep_short),
        onClick = { showSleep = true },
        active = sleep != null,
        modifier = Modifier.testTag("sleepButton").semantics { contentDescription = sleepLabel },
    )
    PlayerAction(
        Icons.Rounded.GraphicEq,
        stringResource(R.string.player_audio),
        onClick = { showAudio = true },
        active = state.skipSilence || (state.titleBoostDb ?: state.defaultBoostDb) > 0,
        modifier = Modifier.testTag("audioButton"),
    )
    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            SleepSheet(state.sleep, onStart = { onStartSleep(it); showSleep = false }, onCancel = { onCancelSleep(); showSleep = false })
        }
    }
    if (showAudio) {
        ModalBottomSheet(onDismissRequest = { showAudio = false }) { AudioSheet(state, onSkipSilence, onBoost) }
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
private fun SleepSheet(sleep: SleepTimerUi?, onStart: (SleepMode) -> Unit, onCancel: () -> Unit) {
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
        if (sleep != null) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp).testTag("sleepCancel")) { Text(stringResource(R.string.sleep_cancel)) }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AudioSheet(state: ComfortUiState, onSkipSilence: (Boolean) -> Unit, onBoost: (Int?) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("audioSheet")) {
        Text(stringResource(R.string.player_audio), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_skip_silence)) },
            supportingContent = { Text(stringResource(R.string.audio_skip_silence_hint)) },
            trailingContent = { Switch(checked = state.skipSilence, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().selectable(selected = state.skipSilence, role = Role.Switch) { onSkipSilence(!state.skipSilence) }
                .testTag("skipSilence"),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_boost)) },
            supportingContent = { Text(stringResource(R.string.audio_boost_hint)) },
        )
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.titleBoostDb == null,
                onClick = { onBoost(null) },
                label = { Text(stringResource(R.string.audio_boost_default, boostLabel(state.defaultBoostDb))) },
                modifier = Modifier.testTag("boost:default"),
            )
            PlaybackSettings.BOOST_CHOICES.forEach { db ->
                FilterChip(
                    selected = state.titleBoostDb == db,
                    onClick = { onBoost(db) },
                    label = { Text(boostLabel(db)) },
                    modifier = Modifier.testTag("boost:$db"),
                )
            }
        }
    }
}
