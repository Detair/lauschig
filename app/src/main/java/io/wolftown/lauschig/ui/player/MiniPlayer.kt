package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.ui.theme.coverTintTarget
import io.wolftown.lauschig.ui.theme.controlColorOn
import io.wolftown.lauschig.ui.theme.TINT_FADE_MS
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import io.wolftown.lauschig.ui.common.ProgressLine
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayerUiState
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel

/** Height/width of the Play/Pause hit area, shared with the full player's secondary buttons' minimum. */
val MiniToggleSize = 56.dp

/**
 * Persistent bar above the bottom navigation, tinted with the cover's colour; a thin line along its top edge shows the
 * chapter's progress. Renders nothing while no item is loaded.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** The cover's server version once known (null until then: no server cover yet, so it is not loaded twice). */
    coverVersion: Long? = 0,
) {
    if (state.itemId == null) return
    val chapterLength = state.chapterEndSec - state.chapterStartSec
    val progress = if (state.chapterIndex >= 0 && chapterLength > 0) {
        ((state.positionSec - state.chapterStartSec) / chapterLength).toFloat()
    } else if (state.durationSec > 0) {
        (state.positionSec / state.durationSec).toFloat()
    } else 0f
    val openLabel = stringResource(R.string.player_mini_open)
    val model = if (coverVersion == null && state.coverPath == null) null
    else coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, coverVersion ?: 0)
    val scheme = MaterialTheme.colorScheme
    val target = coverTintTarget(model, coverVersion ?: 0, scheme.surfaceContainerHigh)
    val container by animateColorAsState(target, tween(TINT_FADE_MS), label = "miniTint")
    // The tinted bar sets its text colour itself (a blended colour is no theme role) and keeps the line at 3:1.
    Surface(modifier.fillMaxWidth().testTag("miniPlayer"), color = container, contentColor = scheme.onSurface) {
        Column {
            ProgressLine({ progress }, Modifier.testTag("miniProgress"), color = scheme.controlColorOn(target))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = MiniToggleSize).clip(CoverShapes.Mini).clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(model, state.title, Modifier.size(48.dp), shape = CoverShapes.Mini, initialsStyle = MaterialTheme.typography.titleSmall)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            SourceIndicator(playSource(state.itemId, state.isStreaming), Modifier.padding(start = 6.dp), showLabel = false)
                        }
                        // The throttle hint only while loading: during playback the chapter title matters more.
                        val sub = state.error?.let { stringResource(it) }
                            ?: state.streamLoad?.let { if (state.downloadThrottled) stringResource(R.string.player_download_throttled) else streamLoadShort(it) }
                            ?: state.chapterTitle
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
                    if (state.isLoading) {
                        LoadingIndicator(Modifier.size(28.dp))
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}
