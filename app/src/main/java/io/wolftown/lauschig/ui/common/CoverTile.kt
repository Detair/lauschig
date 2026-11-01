package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.absapi.library.coverUrl
import java.io.File

/**
 * Cover with series badge (top start), offline/downloading icon (bottom end), optional play button (bottom start)
 * and progress bar (bottom). Tap = [onClick], long-press = [onLongClick] (menu), the play button = [onPlay].
 * Cover source: local file if present, else the server URL only while online, else the title initials
 * (which are always drawn underneath, so a failed load degrades to the placeholder).
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
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) stringResource(R.string.cover_actions) else null)
            .semantics(mergeDescendants = true) { if (unavailable) stateDescription = unavailableText },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).alpha(if (unavailable) 0.38f else 1f).clip(RoundedCornerShape(8.dp))) {
            Initials(item.title)
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.seriesSequence?.takeIf { it.isNotBlank() }?.let { seq ->
                val description = stringResource(R.string.cover_badge_description, seq)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart).clearAndSetSemantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.cover_badge_text, seq),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (onPlay != null && !unavailable) {
                val playLabel = stringResource(R.string.cover_play, item.title)
                FilledIconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 10.dp).size(36.dp).testTag("coverPlay"),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = playLabel, modifier = Modifier.size(24.dp))
                }
            }
            if (item.isDownloading) {
                Icon(
                    Icons.Rounded.Downloading,
                    contentDescription = stringResource(R.string.cover_downloading),
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 4.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50))
                        .padding(2.dp)
                        .size(16.dp)
                        .testTag("coverDownloading"),
                )
            }
            if (item.isDownloaded) {
                Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = stringResource(R.string.cover_offline_available),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 6.dp, end = 4.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        .padding(2.dp)
                        .size(16.dp),
                )
            }
            if (item.progress > 0f && !item.isFinished) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().testTag("coverProgress"),
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
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

@Composable
internal fun Initials(title: String) {
    val initials = remember(title) {
        title.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Local file first, else the server URL (only while [base] is non-null, i.e. online), else null. */
fun coverModel(base: okhttp3.HttpUrl?, itemId: String, coverPath: String?, coverVersion: Long): Any? = when {
    coverPath != null -> File(coverPath)
    base != null -> coverUrl(base, itemId).newBuilder()
        .apply { if (coverVersion > 0) addQueryParameter("ts", coverVersion.toString()) }
        .build().toString()
    else -> null
}
