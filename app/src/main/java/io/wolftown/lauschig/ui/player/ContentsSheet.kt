package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.detail.chapterItems
import io.wolftown.lauschig.ui.theme.Spacing
import kotlinx.coroutines.launch

/** The tabs of the player's contents sheet, in their order. */
enum class ContentsTab(val label: Int) {
    Chapters(R.string.player_chapters),
    Queue(R.string.player_queue),
    Bookmarks(R.string.player_bookmarks),
    History(R.string.player_history),
}

/**
 * The contents of the queue, bookmark and history tabs (they need Hilt, so the route supplies them). Each gets a
 * `done` callback that closes the sheet (after a jump, a start from the queue).
 */
@Immutable
class PlayerContents(
    val queue: @Composable (done: () -> Unit) -> Unit = {},
    val bookmarks: @Composable (done: () -> Unit) -> Unit = {},
    val history: @Composable (done: () -> Unit) -> Unit = {},
)

/** The tab the sheet opens on: the chapters if the title has any, else the queue. */
fun initialContentsTab(hasChapters: Boolean): ContentsTab = if (hasChapters) ContentsTab.Chapters else ContentsTab.Queue

/**
 * The wide banner under the player's tiles: "Kapitel & Wiedergabeliste" with the current chapter ("Aktuell: Kap. 3 / 12"),
 * opening the [ContentsSheet].
 */
@Composable
fun ContentsBanner(chapters: List<ChapterRow>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = chapters.indexOfFirst { it.isCurrent }
    val subtitle = if (chapters.isNotEmpty() && current >= 0) stringResource(R.string.player_contents_current, current + 1, chapters.size)
    else stringResource(R.string.player_contents_no_chapters)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("contentsBanner"),
    ) {
        Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                Text(stringResource(R.string.player_contents), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One sheet for everything inside the title: chapters, the queue, bookmarks and the listening history, as tabs. It
 * keeps one height whatever the tab, so switching never makes it jump.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    initialTab: ContentsTab,
    chapters: List<ChapterRow>,
    onChapter: (Int) -> Unit,
    contents: PlayerContents,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val done: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("contentsSheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()) {
            // On the sheet's own colour, so the tabs read as part of the sheet, not as a bar on it.
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = Spacing.s, containerColor = Color.Transparent) {
                ContentsTab.entries.forEach { t ->
                    Tab(
                        selected = t == tab,
                        onClick = { tab = t },
                        text = { Text(stringResource(t.label), maxLines = 1) },
                        modifier = Modifier.testTag("contentsTab:${t.name}"),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ContentsTab.Chapters -> ChaptersTab(chapters, onChapter = { onChapter(it); done() })
                    ContentsTab.Queue -> contents.queue(done)
                    ContentsTab.Bookmarks -> contents.bookmarks(done)
                    ContentsTab.History -> contents.history(done)
                }
            }
        }
    }
}

@Composable
private fun ChaptersTab(chapters: List<ChapterRow>, onChapter: (Int) -> Unit) {
    if (chapters.isEmpty()) {
        Text(
            stringResource(R.string.player_contents_chapters_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
        )
        return
    }
    // Opens with the current chapter in view (one row of the previous one above it).
    val current = chapters.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    LazyColumn(Modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.Top) { chapterItems(chapters, onChapter) }
}
