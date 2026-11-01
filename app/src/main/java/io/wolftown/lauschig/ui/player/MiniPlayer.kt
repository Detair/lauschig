package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.common.Initials
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel

/** Height/width of the Play/Pause hit area, shared with the full player's secondary buttons' minimum. */
val MiniToggleSize = 56.dp

/** Persistent bar above the bottom navigation; renders nothing while no item is loaded. */
@Composable
fun MiniPlayer(state: PlayerUiState, onToggle: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (state.itemId == null) return
    val chapterLength = state.chapterEndSec - state.chapterStartSec
    val progress = if (state.chapterIndex >= 0 && chapterLength > 0) {
        ((state.positionSec - state.chapterStartSec) / chapterLength).toFloat()
    } else if (state.durationSec > 0) {
        (state.positionSec / state.durationSec).toFloat()
    } else 0f
    val openLabel = stringResource(R.string.player_mini_open)
    val model = coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, 0)
    Surface(modifier.fillMaxWidth().testTag("miniPlayer"), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) {
                        Initials(state.title)
                        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = state.error?.let { stringResource(it) } ?: state.chapterTitle
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(onClick = onToggle, modifier = Modifier.size(MiniToggleSize)) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("miniProgress"), gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

