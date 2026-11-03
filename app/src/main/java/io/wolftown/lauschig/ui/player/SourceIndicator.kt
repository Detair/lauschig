package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PhoneAndroid
import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R

/** Where the loaded title plays from. */
enum class PlaySource { STREAM, DOWNLOAD, DEVICE }

/** A device title (`local:` key) is neither streamed nor "downloaded": it only lives on this device. */
fun playSource(itemId: String?, isStreaming: Boolean): PlaySource = when {
    DeviceLibrary.isLocalKey(itemId) -> PlaySource.DEVICE
    isStreaming -> PlaySource.STREAM
    else -> PlaySource.DOWNLOAD
}

/** Where the loaded title plays from: the server (cloud), a download, or a file of this device. */
@Composable
fun SourceIndicator(source: PlaySource, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val label = stringResource(
        when (source) {
            PlaySource.STREAM -> R.string.source_streaming
            PlaySource.DOWNLOAD -> R.string.source_local
            PlaySource.DEVICE -> R.string.source_device
        },
    )
    val icon = when (source) {
        PlaySource.STREAM -> Icons.Rounded.Cloud
        PlaySource.DOWNLOAD -> Icons.Rounded.DownloadDone
        PlaySource.DEVICE -> Icons.Rounded.PhoneAndroid
    }
    val tag = when (source) {
        PlaySource.STREAM -> "sourceStreaming"
        PlaySource.DOWNLOAD -> "sourceLocal"
        PlaySource.DEVICE -> "sourceDevice"
    }
    Row(modifier.testTag(tag), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = if (showLabel) null else label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Spinner in place of play/pause while a title is being prepared or buffering. */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val label = stringResource(R.string.player_loading)
    CircularProgressIndicator(modifier.semantics { contentDescription = label }.testTag("playerLoading"), color = color, strokeWidth = 3.dp)
}
