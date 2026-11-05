package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.EmptyState
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.detail.ChapterSearch
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
    // Up here, so it survives a look at the queue and back; a new sheet starts without it.
    var chapterQuery by rememberSaveable { mutableStateOf("") }
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
                    ContentsTab.Chapters -> ChaptersTab(chapters, chapterQuery, { chapterQuery = it }, onChapter = { onChapter(it); done() })
                    ContentsTab.Queue -> contents.queue(done)
                    ContentsTab.Bookmarks -> contents.bookmarks(done)
                    ContentsTab.History -> contents.history(done)
                }
            }
        }
    }
}

/** From this many chapters on the tab has a search field; shorter lists fit on a screen or two. */
const val CHAPTER_SEARCH_FROM = 10

@Composable
private fun ChaptersTab(chapters: List<ChapterRow>, query: String, onQuery: (String) -> Unit, onChapter: (Int) -> Unit) {
    if (chapters.isEmpty()) {
        Text(
            stringResource(R.string.player_contents_chapters_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
        )
        return
    }
    val searchable = chapters.size >= CHAPTER_SEARCH_FROM
    val q = if (searchable) query else ""
    val filtering = ChapterSearch.isActive(q)
    val shown = remember(chapters, q) { ChapterSearch.filter(chapters, q) }
    // Opens with the current chapter in view (one row of the previous one above it); a search starts at its first hit.
    val currentTop = (chapters.indexOfFirst { it.isCurrent } - 1).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (filtering) 0 else currentTop)
    var shownFor by remember { mutableStateOf(q) }
    LaunchedEffect(q) {
        if (q == shownFor) return@LaunchedEffect
        shownFor = q
        list.scrollToItem(if (filtering) 0 else currentTop)
    }
    val focus = LocalFocusManager.current
    // Dragging the hits down lets the keyboard go, so the whole list shows.
    val dropKeyboard = remember(focus) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) focus.clearFocus()
                return Offset.Zero
            }
        }
    }
    // With the keyboard up the sheet's window ends above it, so the list does too (emulator, with and without this
    // padding: the sheet stays 85 % of the window and moves up a little). The padding keeps the hits visible should a
    // window not shrink.
    Column(Modifier.fillMaxSize().imePadding()) {
        if (searchable) ChapterSearchField(query, onQuery, onDone = { focus.clearFocus() })
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.SearchOff,
                    stringResource(R.string.chapter_search_nothing, q.trim()),
                    Modifier.testTag("chapterSearchEmpty"),
                    supporting = stringResource(R.string.chapter_search_nothing_hint),
                    verticalPadding = Spacing.xl,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).nestedScroll(dropKeyboard).testTag("chapterList"), state = list, verticalArrangement = Arrangement.Top) {
                chapterItems(shown, onChapter, numbered = filtering)
            }
        }
    }
}

@Composable
private fun ChapterSearchField(query: String, onQuery: (String) -> Unit, onDone: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.chapter_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag("chapterSearchClear")) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s).testTag("chapterSearch"),
    )
}
