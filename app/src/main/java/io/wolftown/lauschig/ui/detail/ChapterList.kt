package io.wolftown.lauschig.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** [numbered]: each row starts with its chapter number (search hits, which may not say where they are). */
fun LazyListScope.chapterItems(chapters: List<ChapterRow>, onChapter: (Int) -> Unit, numbered: Boolean = false) {
    itemsIndexed(chapters, key = { _, c -> c.index }) { _, c -> ChapterRowItem(c, numbered, onClick = { onChapter(c.index) }) }
}

@Composable
private fun ChapterRowItem(chapter: ChapterRow, numbered: Boolean, onClick: () -> Unit) {
    val container = if (chapter.isCurrent) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(container)
            .selectable(selected = chapter.isCurrent, role = Role.Button, onClick = onClick)
            .then(if (chapter.isCurrent) Modifier.testTag("currentChapter") else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (numbered) {
            val spokenNumber = stringResource(R.string.chapter_number_spoken, chapter.index + 1)
            Text(
                (chapter.index + 1).toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                // Read as „Kapitel 12“, not a bare number, within the row.
                modifier = Modifier.widthIn(min = 28.dp).testTag("chapterNumber").clearAndSetSemantics { text = AnnotatedString(spokenNumber) },
            )
        }
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


