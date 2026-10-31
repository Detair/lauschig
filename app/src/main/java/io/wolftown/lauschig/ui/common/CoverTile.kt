package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * Cover with series badge (top start), offline icon (bottom end) and progress bar (bottom).
 * Cover source: local file if present, else the server URL only while online, else the title initials
 * (which are always drawn underneath, so a failed load degrades to the placeholder).
 */
@Composable
fun CoverTile(item: ItemSummary, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val base = LocalServerBaseUrl.current
    val unavailable = base == null && !item.isDownloaded
    val unavailableText = stringResource(R.string.cover_not_available_offline)
    val model: Any? = when {
        item.coverPath != null -> File(item.coverPath)
        base != null -> coverUrl(base, item.id).toString()
        else -> null
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .alpha(if (unavailable) 0.38f else 1f)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { if (unavailable) stateDescription = unavailableText },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
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
                    modifier = Modifier.align(Alignment.TopStart).semantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.cover_badge_text, seq),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
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
private fun Initials(title: String) {
    val initials = remember(title) {
        title.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
