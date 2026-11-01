package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DownloadDone
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

/** Where the loaded title plays from: the server (cloud) or the device (downloaded). */
@Composable
fun SourceIndicator(isStreaming: Boolean, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val label = stringResource(if (isStreaming) R.string.source_streaming else R.string.source_local)
    val icon = if (isStreaming) Icons.Rounded.Cloud else Icons.Rounded.DownloadDone
    Row(modifier.testTag(if (isStreaming) "sourceStreaming" else "sourceLocal"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
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
