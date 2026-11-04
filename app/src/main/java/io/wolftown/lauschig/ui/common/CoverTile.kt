package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.background
import io.wolftown.lauschig.playback.PlayableKey
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.absapi.library.coverUrl
import io.wolftown.lauschig.local.DeviceLibrary
import java.io.File

/**
 * Cover with series badge (top start), offline/downloading badge (top end), optional play button (bottom end) and
 * progress line (bottom). Tap = [onClick], long-press = [onLongClick] (menu), the play button = [onPlay].
 * Cover source: local file if present, else the server URL only while online, else the title's placeholder
 * (which is always drawn underneath, so a failed load degrades to the placeholder).
 */
@Composable
fun CoverTile(
    item: ItemSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
) {
    val base = LocalServerBaseUrl.current
    val unavailable = LocalOfflineDimming.current && !item.isDownloaded
    val unavailableText = stringResource(R.string.cover_not_available_offline)
    val model = coverModel(base, item.id, item.coverPath, item.coverVersion)
    Column(
        modifier
            .clip(CoverShapes.Tile)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) stringResource(R.string.cover_actions) else null)
            .semantics(mergeDescendants = true) { if (unavailable) stateDescription = unavailableText },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (unavailable) 0.38f else 1f).clip(CoverShapes.Tile)) {
            CoverArt(model, item.title, Modifier.matchParentSize())
            item.seriesSequence?.takeIf { it.isNotBlank() }?.let { seq ->
                val description = stringResource(R.string.cover_badge_description, seq)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(bottomEnd = 10.dp),
                    modifier = Modifier.align(Alignment.TopStart).clearAndSetSemantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.cover_badge_text, seq),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp, end = 7.dp, top = 3.dp, bottom = 3.dp),
                    )
                }
            }
            if (onPlay != null && !unavailable) {
                val playLabel = stringResource(R.string.cover_play, item.title)
                // Quiet: a translucent scrim with a white icon reads on any cover without hiding much of it.
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = PlayScrim, contentColor = Color.White),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 10.dp).size(36.dp).testTag("coverPlay"),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = playLabel, modifier = Modifier.size(22.dp))
                }
            }
            if (item.isDownloading) {
                StatusBadge(Icons.Rounded.Downloading, stringResource(R.string.cover_downloading), Modifier.align(Alignment.TopEnd).testTag("coverDownloading"))
            }
            if (item.isDownloaded) {
                StatusBadge(Icons.Rounded.DownloadDone, stringResource(R.string.cover_offline_available), Modifier.align(Alignment.TopEnd))
            }
            if (item.progress > 0f && !item.isFinished) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).testTag("coverProgress"),
                    trackColor = Color.Black.copy(alpha = 0.3f),
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** About 45 % black: the play button's backdrop on a cover. */
private val PlayScrim = Color.Black.copy(alpha = 0.45f)

/** A small circle in a cover's corner (downloaded, downloading), in the surface colour so it reads on any cover. */
@Composable
private fun StatusBadge(icon: ImageVector, description: String, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = description,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f), CircleShape)
            .padding(3.dp)
            .size(14.dp),
    )
}

/** Local file first, else the server URL (only while [base] is non-null, i.e. online; never for a device title), else null. */
fun coverModel(base: okhttp3.HttpUrl?, itemId: String, coverPath: String?, coverVersion: Long): Any? = when {
    coverPath != null -> File(coverPath)
    DeviceLibrary.isLocalKey(itemId) -> null
    base != null -> coverUrl(base, PlayableKey.parse(itemId).libraryItemId).newBuilder()
        .apply { if (coverVersion > 0) addQueryParameter("ts", coverVersion.toString()) }
        .build().toString()
    else -> null
}
