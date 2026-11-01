package io.wolftown.lauschig.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import java.util.Locale

/** "mm:ss", or "h:mm:ss" from one hour on. */
fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

fun LazyListScope.chapterItems(chapters: List<ChapterRow>, onChapter: (Int) -> Unit) {
    itemsIndexed(chapters, key = { _, c -> c.index }) { _, c -> ChapterRowItem(c, onClick = { onChapter(c.index) }) }
}

@Composable
private fun ChapterRowItem(chapter: ChapterRow, onClick: () -> Unit) {
    val container = if (chapter.isCurrent) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(container)
            .clickable(onClick = onClick)
            .semantics { selected = chapter.isCurrent }
            .then(if (chapter.isCurrent) Modifier.testTag("currentChapter") else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            chapter.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (chapter.isCurrent) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (chapter.isCurrent && chapter.positionInChapterSec != null) {
                stringResource(R.string.detail_current_chapter_position, formatClock(chapter.positionInChapterSec), formatClock(chapter.durationSec))
            } else {
                formatClock(chapter.durationSec)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


