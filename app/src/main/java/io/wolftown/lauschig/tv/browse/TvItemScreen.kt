package io.wolftown.lauschig.tv.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.common.formatHms
import io.wolftown.lauschig.ui.connection.ConnectionStatusViewModel
import io.wolftown.lauschig.ui.detail.ChapterRow
import io.wolftown.lauschig.ui.detail.ItemDetailUiState
import io.wolftown.lauschig.ui.detail.ItemDetailViewModel
import io.wolftown.lauschig.ui.detail.PrimaryAction
import io.wolftown.lauschig.ui.extras.listenedText
import io.wolftown.lauschig.ui.podcast.ItemKindViewModel
import kotlinx.coroutines.launch

internal const val TAG_PRIMARY = "tvItemPrimary"
internal const val TAG_FROM_START = "tvItemFromStart"
internal const val TAG_CHAPTERS = "tvItemChapters"
internal const val TAG_SERIES_LINE = "tvItemSeries"
internal const val TAG_DESCRIPTION = "tvItemDescription"
internal const val TAG_OFFLINE = "tvItemOffline"
internal const val TAG_ITEM_RETRY = "tvItemRetry"
internal const val TAG_CHAPTER_LIST = "tvItemChapterList"
/** A chapter of the list is tagged with this plus its index. */
internal const val TAG_CHAPTER = "tvItemChapter:"

/**
 * A title ([io.wolftown.lauschig.ui.nav.ItemDetail]): the phone's view models, as they are. Every way to start playing goes
 * on to the player with [onPlay].
 */
@Composable
fun TvItemRoute(
    onPlay: () -> Unit,
    onOpenSeries: (String) -> Unit,
    viewModel: ItemDetailViewModel = hiltViewModel(),
    kind: ItemKindViewModel = hiltViewModel(),
    connection: ConnectionStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isPodcast by kind.isPodcast.collectAsStateWithLifecycle()
    // Not known yet: neither the book's page nor the podcast's hint, which could not take it back.
    val podcast = isPodcast ?: return TvMessage(stringResource(R.string.tv_loading))
    val once = rememberPlayOnce(onPlay)
    TvItemScreen(
        state = state,
        isPodcast = podcast,
        onPrimary = { once { viewModel.onPrimary() } },
        onFromStart = { once { viewModel.playFromStart() } },
        onChapter = { once { viewModel.onChapter(it) } },
        onOpenSeries = onOpenSeries,
        onRetry = connection::reconnect,
    )
}

/**
 * Starts playing at most once until the page shows again: a second OK while the page is still on its way to the player
 * would otherwise start the title twice. [onPlay] follows the start.
 */
@Composable
internal fun rememberPlayOnce(onPlay: () -> Unit): (start: () -> Unit) -> Unit {
    val started = remember { Holder() }
    val currentOnPlay by rememberUpdatedState(onPlay)
    // Back from the player, the page can start again.
    LifecycleResumeEffect(Unit) {
        started.value = false
        onPauseOrDispose {}
    }
    return remember {
        { start ->
            if (!started.value) {
                started.value = true
                start()
                currentOnPlay()
            }
        }
    }
}

/**
 * A title (TV spec: "Titel"): the cover on the left; on the right title, author and narrator, the series (OK opens it),
 * the length, the buttons and the description. The focus starts on the main button. The TV keeps nothing for offline, so
 * without the server ([online] false) the buttons are off and a hint says why, with a way to try again; a title the player
 * already holds plays on. A podcast shows only a hint: podcasts are not on the TV yet (TV spec E6).
 */
@Composable
fun TvItemScreen(
    state: ItemDetailUiState,
    isPodcast: Boolean,
    onPrimary: () -> Unit,
    onFromStart: () -> Unit,
    onChapter: (Int) -> Unit,
    onOpenSeries: (String) -> Unit,
    online: Boolean = !LocalOfflineDimming.current,
    onRetry: () -> Unit = {},
) {
    when {
        isPodcast -> TvMessage(stringResource(R.string.tv_item_podcast))
        !state.found -> TvMessage(stringResource(R.string.detail_not_found))
        else -> ItemContent(state, playable = online || state.playableOffline, onPrimary, onFromStart, onChapter, onOpenSeries, onRetry)
    }
}

@Composable
private fun ItemContent(
    state: ItemDetailUiState,
    playable: Boolean,
    onPrimary: () -> Unit,
    onFromStart: () -> Unit,
    onChapter: (Int) -> Unit,
    onOpenSeries: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val primary = remember { FocusRequester() }
    val chaptersButton = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    var chaptersOpen by rememberSaveable { mutableStateOf(false) }
    val retryHadFocus = remember { Holder() }
    // Offline the only thing to do is to try again, so that is where the focus starts then.
    LaunchedEffect(Unit) { (if (playable) primary else retry).requestFocus() }
    // The server came back while the focus was on "Try again", which now goes: on to the main button, not into nothing.
    LaunchedEffect(playable) { if (playable && retryHadFocus.value) primary.requestFocusWhenPlaced() }
    BackHandler(enabled = chaptersOpen) { chaptersOpen = false }
    val closed = remember { Holder() }
    LaunchedEffect(chaptersOpen) {
        if (!chaptersOpen && closed.value) chaptersButton.requestFocusWhenPlaced()
        closed.value = chaptersOpen
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            CoverArt(
                coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, state.coverVersion), state.title,
                Modifier.width(COVER_SIZE).aspectRatio(1f), shape = RoundedCornerShape(12.dp),
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.subtitle?.let { Secondary(it) }
                state.author?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                state.narrator?.let { Secondary(stringResource(R.string.detail_quick_narrator, it)) }
                state.durationSec.takeIf { it > 0 }?.let { Secondary(stringResource(R.string.detail_quick_length, listenedText(it))) }
                SeriesLine(state, onOpenSeries)
                // Room between the buttons for the focused one to grow.
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ActionButton(primaryLabel(state.primary), Icons.Rounded.PlayArrow, playable, onPrimary, Modifier.focusRequester(primary).testTag(TAG_PRIMARY), filled = true)
                    // Only with progress: without any, the main button already starts at the beginning.
                    if (state.primary != PrimaryAction.Play) {
                        ActionButton(stringResource(R.string.tv_item_from_start), Icons.Rounded.Replay, playable, onFromStart, Modifier.testTag(TAG_FROM_START))
                    }
                    if (state.chapters.isNotEmpty()) {
                        ActionButton(
                            stringResource(R.string.detail_chapters), Icons.AutoMirrored.Rounded.List, playable, { chaptersOpen = true },
                            Modifier.focusRequester(chaptersButton).testTag(TAG_CHAPTERS),
                        )
                    }
                }
                if (!playable) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.tv_item_offline), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false).testTag(TAG_OFFLINE),
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.focusRequester(retry).onFocusChanged { if (it.isFocused) retryHadFocus.value = true }.testTag(TAG_ITEM_RETRY),
                        ) { Text(stringResource(R.string.tv_retry)) }
                    }
                }
                state.description?.let { Description(it, up = primary, Modifier.weight(1f).padding(top = 12.dp)) }
            }
        }
        if (chaptersOpen) {
            ChapterPanel(
                state.chapters,
                onChapter = { index ->
                    // Coming back from the player, the page shows without the list.
                    chaptersOpen = false
                    onChapter(index)
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private val COVER_SIZE = 300.dp

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun primaryLabel(p: PrimaryAction): String = when (p) {
    PrimaryAction.Play -> stringResource(R.string.detail_play)
    is PrimaryAction.Continue ->
        if (p.chapterNumber != null) stringResource(R.string.detail_continue_chapter, p.chapterNumber) else stringResource(R.string.detail_continue)
    PrimaryAction.ListenAgain -> stringResource(R.string.detail_listen_again)
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, filled: Boolean = false) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
    // Focusable also while it is off: the person sees it is there, and the hint below says why it is off.
    // The main button in the accent colour: the one to press, seen at a glance from the sofa.
    if (filled) {
        val scheme = MaterialTheme.colorScheme
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier,
            colors = ButtonDefaults.colors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
        ) { content() }
    }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
}

/** "Series: name · Book 3"; OK opens the series. A device title's series leads nowhere, as on the phone. */
@Composable
private fun SeriesLine(state: ItemDetailUiState, onOpenSeries: (String) -> Unit) {
    val name = state.seriesName ?: return
    val label = state.seriesSequence?.let { stringResource(R.string.detail_series_band, name, it) } ?: name
    val text = stringResource(R.string.detail_series_line, label)
    val scheme = MaterialTheme.colorScheme
    val row: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.isLocal) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
    if (state.isLocal) {
        Box(Modifier.testTag(TAG_SERIES_LINE)) { row() }
        return
    }
    Surface(
        onClick = { onOpenSeries(name) },
        modifier = Modifier.semantics { contentDescription = text }.testTag(TAG_SERIES_LINE),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = scheme.primary,
            focusedContainerColor = scheme.primary, focusedContentColor = scheme.onPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) { row() }
}

/**
 * The description. A long one takes the focus below the buttons, and then up and down roll it; at its top, up goes back to
 * the main button. A short one that fits is no stop for the focus.
 */
@Composable
private fun Description(text: String, up: FocusRequester, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frame = if (focused) MaterialTheme.colorScheme.onSurface else Color.Transparent
    val label = stringResource(R.string.tv_item_description)
    Box(
        modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, frame), RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = scroll.viewportSize * 0.75f
                when {
                    event.key == Key.DirectionDown && scroll.canScrollForward -> { scope.launch { scroll.animateScrollBy(step) }; true }
                    event.key == Key.DirectionUp && scroll.canScrollBackward -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                    else -> false
                }
            }
            .focusProperties {
                canFocus = scroll.maxValue > 0
                this.up = up
            }
            .semantics { contentDescription = label }
            .focusable(interactionSource = interaction)
            .testTag(TAG_DESCRIPTION),
    ) {
        Text(
            text, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = false).padding(12.dp),
        )
    }
}

/**
 * The chapters as a list on the right (TV spec: a side panel instead of the phone's sheet). It opens on the current chapter;
 * OK on one plays it; Back closes the list. The focus stays inside while it is open.
 */
@Composable
private fun ChapterPanel(chapters: List<ChapterRow>, onChapter: (Int) -> Unit, modifier: Modifier) {
    val current = chapters.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
    val target = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        list.scrollToItem(current)
        target.requestFocusWhenPlaced()
    }
    Surface(
        modifier.fillMaxHeight().width(PANEL_WIDTH),
        shape = RectangleShape,
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 24.dp, end = TvDimens.overscanHorizontal, top = TvDimens.overscanVertical, bottom = TvDimens.overscanVertical)) {
            Text(stringResource(R.string.detail_chapters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(
                Modifier
                    .testTag(TAG_CHAPTER_LIST)
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
                state = list,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(chapters, key = { _, c -> c.index }) { i, chapter ->
                    ListItem(
                        selected = chapter.isCurrent,
                        onClick = { onChapter(chapter.index) },
                        headlineContent = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(formatHms(chapter.durationSec), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier
                            .then(if (i == current) Modifier.focusRequester(target) else Modifier)
                            .testTag(TAG_CHAPTER + chapter.index),
                    )
                }
            }
        }
    }
}

private val PANEL_WIDTH = 440.dp

/** A plain flag, read and written outside of snapshots. */
private class Holder {
    var value = false
}
