package io.wolftown.lauschig.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import io.wolftown.lauschig.R
import io.wolftown.lauschig.cast.CastActivity
import io.wolftown.lauschig.cast.CastRuntime
import io.wolftown.lauschig.cast.CastState
import io.wolftown.lauschig.cast.CastUi
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The full player is where Cast starts: the first time it opens, Google's framework comes up (never earlier, see
 * [CastRuntime]), and while it is on screen devices are looked for.
 */
@HiltViewModel
class CastViewModel @Inject constructor(private val runtime: CastRuntime, state: CastState) : ViewModel() {
    val ui: StateFlow<CastUi> = state.ui

    init {
        runtime.start()
    }

    fun discovery(on: Boolean) = runtime.setDiscovery(on)
}

/** The Cast button of the player, for the title [itemId]; nothing without a device (or without Play Services). */
@Composable
fun CastControl(itemId: String?, viewModel: CastViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LifecycleStartEffect(Unit) {
        viewModel.discovery(true)
        onStopOrDispose { viewModel.discovery(false) }
    }
    CastButton(ui, deviceTitle = DeviceLibrary.isLocalKey(itemId))
}

/** A device title cannot go to a receiver: its button says why instead of offering devices that would fail. */
@OptIn(UnstableApi::class)
@Composable
fun CastButton(ui: CastUi, deviceTitle: Boolean) {
    if (!ui.showButton) return
    if (deviceTitle) DeviceTitleCastButton() else MediaRouteButton(Modifier.testTag("castButton"))
}

@Composable
private fun DeviceTitleCastButton() {
    var explain by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { explain = true }, modifier = Modifier.testTag("castButtonDeviceTitle")) {
        Icon(Icons.Rounded.Cast, stringResource(R.string.cast_device_title_button))
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.cast_device_title_heading)) },
            text = { Text(stringResource(R.string.cast_device_title_text)) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.cast_understood)) } },
        )
    }
}

/** "Playing on Living Room TV" under the title, or what is true instead; a change is announced by TalkBack. */
@Composable
fun CastOutputLine(deviceName: String, activity: CastActivity, modifier: Modifier = Modifier) {
    val named = deviceName.isNotBlank()
    val text = when (activity) {
        CastActivity.NONE -> return
        CastActivity.PLAYING -> if (named) stringResource(R.string.cast_playing_on, deviceName) else stringResource(R.string.cast_playing_elsewhere)
        CastActivity.CONNECTED -> if (named) stringResource(R.string.cast_connected_to, deviceName) else stringResource(R.string.cast_connected_elsewhere)
        CastActivity.SUSPENDED -> stringResource(R.string.cast_suspended_line)
    }
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("castOutput"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(
            text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, overflow = TextOverflow.Ellipsis,
            // The one that explains something may take its room; a name stays on its line.
            maxLines = if (activity == CastActivity.SUSPENDED) 3 else 1,
        )
    }
}
