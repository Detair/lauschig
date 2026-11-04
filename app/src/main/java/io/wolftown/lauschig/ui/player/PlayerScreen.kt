package io.wolftown.lauschig.ui.player

import io.wolftown.lauschig.local.DeviceLibrary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.History
import io.wolftown.lauschig.ui.extras.BookmarksSheet
import io.wolftown.lauschig.ui.extras.HistorySheet
import io.wolftown.lauschig.ui.extras.QueueSheet
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberModalBottomSheetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.common.DownloadIconButton
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import io.wolftown.lauschig.ui.theme.animateCoverTint
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.QuickDownload
import io.wolftown.lauschig.ui.detail.rememberNotificationPermissionOnce
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.detail.chapterItems
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun PlayerRoute(
    onClose: () -> Unit,
    onOpenDetails: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    actions: ItemActionsViewModel = hiltViewModel(),
    comfortViewModel: ComfortViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val comfort by comfortViewModel.state.collectAsStateWithLifecycle()
    val itemId = state.itemId
    val download by remember(itemId) { actions.downloadState(itemId) }.collectAsStateWithLifecycle(QuickDownload.None)
    val askNotifications = rememberNotificationPermissionOnce()
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    PlayerScreen(
        state, onClose, modifier,
        // The player's id is the playable key; details open on the library item (the podcast for an episode).
        onOpenDetails = { itemId?.let { onOpenDetails(PlayableKey.parse(it).libraryItemId) } },
        download = download,
        onDownload = { itemId?.let { actions.download(it); askNotifications() } },
        onDeleteDownload = { itemId?.let(actions::deleteDownload) },
        topActions = { itemId?.let { ProgressMenuButton(it, viewModel = actions) } },
        finished = { itemId?.let { FinishedCard(it, { id -> onOpenDetails(PlayableKey.parse(id).libraryItemId) }, Modifier.padding(vertical = 8.dp), viewModel = actions) } },
        onToggle = viewModel::togglePlayPause,
        onSeekBy = viewModel::seekBy,
        onSeekInChapter = viewModel::seekInChapter,
        onPrevious = viewModel::previousChapter,
        onNext = viewModel::nextChapter,
        onChapter = viewModel::seekToChapter,
        onSpeed = viewModel::setSpeed,
        extras = {
            PlayerExtraButtons(onBookmarks = { sheet = PlayerSheet.Bookmarks }, onQueue = { sheet = PlayerSheet.Queue }, onHistory = { sheet = PlayerSheet.History })
        },
        comfort = {
            ComfortButtons(
                comfort,
                onStartSleep = comfortViewModel::startSleep,
                onCancelSleep = comfortViewModel::cancelSleep,
                onSkipSilence = comfortViewModel::setSkipSilence,
                onBoost = comfortViewModel::setBoost,
            )
        },
        comfortHint = { ShakeHint(comfort) },
    )
    val close = { sheet = null }
    when (sheet) {
        PlayerSheet.Bookmarks -> itemId?.let { BookmarksSheet(it, state.chapterStartSec + state.chapterPositionSec, close) }
        PlayerSheet.History -> itemId?.let { HistorySheet(it, close) }
        PlayerSheet.Queue -> QueueSheet(close)
        null -> Unit
    }
}

enum class PlayerSheet { Bookmarks, Queue, History }

/** Bookmarks, queue and listening history buttons of the player's action area. */
@Composable
fun RowScope.PlayerExtraButtons(onBookmarks: () -> Unit, onQueue: () -> Unit, onHistory: () -> Unit) {
    PlayerAction(Icons.Rounded.Bookmarks, stringResource(R.string.player_bookmarks), onBookmarks, Modifier.testTag("bookmarksButton"))
    PlayerAction(Icons.AutoMirrored.Rounded.QueueMusic, stringResource(R.string.player_queue), onQueue, Modifier.testTag("queueButton"))
    PlayerAction(Icons.Rounded.History, stringResource(R.string.player_history), onHistory, Modifier.testTag("historyButton"))
}

/** "1,25×" in the user's locale. */
fun formatSpeed(speed: Float): String = speedFormat().format(speed) + "×"

private val speedFormats = java.util.concurrent.ConcurrentHashMap<java.util.Locale, NumberFormat>()

/** One cached [NumberFormat] per default locale (formats are not thread-safe, but only the UI thread uses this). */
private fun speedFormat(): NumberFormat = speedFormats.getOrPut(java.util.Locale.getDefault()) {
    NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 2 }
}

/** From this width on, a window wider than high shows the cover beside the controls (phones in landscape, tablets). */
private val WIDE_MIN_WIDTH = 600.dp

/**
 * From this height on, the wide layout puts the action grid under the transport controls on the end side (the start side
 * is the cover alone, as large as fits). The end side then needs about 410-440 dp (top bar, title and chapter, progress,
 * transport, two button rows), so 480 dp leaves some room for a longer title; the test tablet has about 520 dp of
 * content height with its system bars. Below it (phones) the grid stays under the cover and the cover gives up height
 * for it, so nothing needs scrolling; a taller title or a larger font than the room allows scrolls the end side, with
 * the transport controls first.
 */
private val END_COLUMN_MIN_HEIGHT = 480.dp

internal fun actionGridUnderTransport(height: Dp): Boolean = height >= END_COLUMN_MIN_HEIGHT

/** The cover never shrinks below this; when even that does not fit, the player scrolls. */
private val MIN_COVER = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerScreenState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {},
    onSeekBy: (Int) -> Unit = {},
    onSeekInChapter: (Float) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onChapter: (Int) -> Unit = {},
    onSpeed: (Float) -> Unit = {},
    onOpenDetails: () -> Unit = {},
    download: QuickDownload = QuickDownload.None,
    onDownload: () -> Unit = {},
    onDeleteDownload: () -> Unit = {},
    topActions: @Composable () -> Unit = {},
    finished: @Composable () -> Unit = {},
    /** Sleep timer and audio buttons ([ComfortButtons]) of the action area; shown only while a title is loaded. */
    comfort: @Composable RowScope.() -> Unit = {},
    /** Under the action area, e.g. "shake to extend" ([ShakeHint]); shown only while a title is loaded. */
    comfortHint: @Composable () -> Unit = {},
    /** Bookmarks, queue, history ([PlayerExtraButtons]); shown only while a title is loaded. */
    extras: @Composable RowScope.() -> Unit = {},
) {
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    // While dragging, the slider keeps its own value; the seek happens on release only.
    var dragging by remember { mutableStateOf<Float?>(null) }
    // After release the released value stays until the player reflects the seek (or a short timeout), so the thumb never snaps back.
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pending) { if (pending != null) { delay(2_000); pending = null } }
    LaunchedEffect(state.chapterProgress, state.chapterPositionSec) {
        pending?.let { if (abs(it * state.chapterDurationSec - state.chapterPositionSec) < 2.0) pending = null }
    }
    val hasChapters = state.chapters.isNotEmpty()
    val loaded = state.itemId != null
    val detailsLabel = stringResource(R.string.player_open_details)
    val art = state.itemId?.let { coverModel(LocalServerBaseUrl.current, it, state.coverPath, 0) }
    val surface = MaterialTheme.colorScheme.surface
    // The signature: the cover's tint at the top, fading into the surface at 60 % of the height.
    val tint = animateCoverTint(art, surface)
    val tabular = remember { TextStyle(fontFeatureSettings = "tnum") }

    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_close)) }
            // Where it plays from sits in the top bar's free middle, which keeps the title block short.
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                if (state.itemId != null) SourceIndicator(playSource(state.itemId, state.isStreaming))
            }
            if (state.itemId != null) {
                if (!DeviceLibrary.isLocalKey(state.itemId)) DownloadIconButton(download, onDownload = onDownload, onDelete = onDeleteDownload)
                IconButton(onClick = onOpenDetails, modifier = Modifier.testTag("playerDetails")) { Icon(Icons.Rounded.Info, detailsLabel) }
                topActions()
            }
        }
    }
    val cover: @Composable () -> Unit = {
        CoverArt(
            art, state.title,
            Modifier.fillMaxSize()
                .shadow(16.dp, CoverShapes.Player, ambientColor = CoverShadow, spotColor = CoverShadow)
                .clip(CoverShapes.Player)
                .clickable(onClickLabel = detailsLabel, onClick = onOpenDetails)
                .testTag("playerCover"),
            shape = CoverShapes.Player,
            initialsStyle = MaterialTheme.typography.displaySmall,
        )
    }
    val info: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Text(
                state.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = detailsLabel, onClick = onOpenDetails),
            )
            playerSubtitle(state)?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("playerSubtitle"),
                )
            }
            state.chapterTitle?.let {
                Text(
                    it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            state.error?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            state.streamLoad?.let { load ->
                Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("streamLoad"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        streamLoadText(load),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    load.fraction()?.let { f ->
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            if (state.downloadThrottled) {
                Text(
                    stringResource(R.string.player_download_throttled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("downloadThrottled"),
                )
            }
        }
    }
    val progress: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            val sliderLabel = stringResource(R.string.player_chapter_slider)
            Slider(
                value = dragging ?: pending ?: state.chapterProgress,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeekInChapter(it); pending = it }; dragging = null },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = sliderLabel }.testTag("chapterSlider"),
            )
            // Chapter elapsed and length at the ends, the position in the whole book between them.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val shownElapsed = dragging?.let { io.wolftown.lauschig.ui.detail.formatClock(it * state.chapterDurationSec) } ?: state.chapterElapsedText
                Text(shownElapsed, style = MaterialTheme.typography.labelLarge.merge(tabular))
                Text(
                    stringResource(R.string.player_book_time, state.bookElapsedText, state.bookTotalText),
                    style = MaterialTheme.typography.bodyMedium.merge(tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("bookTime"),
                )
                Text(state.chapterTotalText, style = MaterialTheme.typography.labelLarge.merge(tabular))
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = hasChapters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous_chapter), Modifier.size(32.dp))
            }
            IconButton(onClick = { onSeekBy(-30) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay30, stringResource(R.string.player_back_30), Modifier.size(36.dp))
            }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(80.dp)) {
                if (state.isLoading) {
                    LoadingIndicator(Modifier.size(40.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                        Modifier.size(56.dp),
                    )
                }
            }
            IconButton(onClick = { onSeekBy(30) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward30, stringResource(R.string.player_forward_30), Modifier.size(36.dp))
            }
            IconButton(onClick = onNext, enabled = hasChapters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next_chapter), Modifier.size(32.dp))
            }
        }
    }
    // One compact area instead of three button rows, so a phone shows it without scrolling.
    val actions: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(PlayerActionSpacing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PlayerActionSpacing)) {
                val speedDescription = stringResource(R.string.player_speed_button, formatSpeed(state.speed))
                PlayerAction(
                    Icons.Rounded.Speed, formatSpeed(state.speed), onClick = { showSpeed = true }, active = state.speed != 1f,
                    modifier = Modifier.testTag("speedButton").semantics { contentDescription = speedDescription },
                )
                PlayerAction(
                    Icons.AutoMirrored.Rounded.List, stringResource(R.string.player_chapters), onClick = { showChapters = true },
                    enabled = hasChapters, modifier = Modifier.testTag("chaptersButton"),
                )
                if (loaded) comfort()
            }
            if (loaded) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PlayerActionSpacing)) { extras() }
            if (loaded) comfortHint()
        }
    }

    // The gradient reaches under the status bar and a side cutout too (the page itself sits inside them).
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val reach = Rect(
        bars.getLeft(density, direction).toFloat(), bars.getTop(density).toFloat(),
        bars.getRight(density, direction).toFloat(), 0f,
    )
    BoxWithConstraints(
        modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.verticalGradient(0f to tint.value, 0.6f to surface, startY = -reach.top, endY = size.height),
                topLeft = Offset(-reach.left, -reach.top),
                size = Size(size.width + reach.left + reach.right, size.height + reach.top),
            )
        },
    ) {
        val viewport = constraints.maxHeight
        val viewportDp = maxHeight
        // End side of the wide layouts: top bar, title, progress and transport controls, centred in the height that is
        // left; [withActions] adds the action area under them.
        val endSide: @Composable RowScope.(withActions: Boolean) -> Unit = { withActions ->
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Column(Modifier.heightIn(min = viewportDp)) {
                    topBar()
                    Spacer(Modifier.weight(1f))
                    info()
                    progress()
                    controls()
                    finished()
                    Spacer(Modifier.weight(1f))
                    if (withActions) actions()
                }
            }
        }
        if (maxWidth >= WIDE_MIN_WIDTH && maxWidth > maxHeight) {
            if (actionGridUnderTransport(maxHeight)) {
                // Tablets in landscape: the cover alone on the start side, as large as fits and centred; everything
                // else, the action area last, on the end side.
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(minOf(maxWidth, maxHeight - 32.dp, 560.dp))) { cover() }
                    }
                    endSide(true)
                }
            } else {
                // Phones in landscape: cover with the action area under it on the start side, the rest beside it (the
                // end side has no room left for the action area).
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        CoverFitColumn(viewport, MIN_COVER, maxCover = 560.dp, header = { Spacer(Modifier.height(8.dp)) }, cover = cover, body = actions)
                    }
                    endSide(false)
                }
            }
        } else {
            val maxCover = if (maxWidth >= WIDE_MIN_WIDTH) 520.dp else 280.dp
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                CoverFitColumn(
                    viewport, MIN_COVER, maxCover,
                    header = topBar,
                    cover = cover,
                    body = {
                        Column(Modifier.fillMaxWidth()) {
                            info()
                            progress()
                            controls()
                            finished()
                            actions()
                        }
                    },
                    modifier = Modifier.widthIn(max = 600.dp).padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) { SpeedSheet(state.speed, onSpeed) }
    }
    if (showChapters) {
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { showChapters = false }, sheetState = sheetState) {
            Text(stringResource(R.string.player_chapters), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn {
                chapterItems(state.chapters) { idx ->
                    onChapter(idx)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { showChapters = false }
                }
            }
        }
    }
}

/** The cover's soft shadow. */
private val CoverShadow = Color.Black.copy(alpha = 0.35f)

/**
 * The quieter line under the player's title: the author and the series with its volume, as the detail page shows them
 * ("Autorin · Saga · Band 3"); an author who is the series' own name ("Die drei ???") is said once.
 */
@Composable
internal fun playerSubtitle(state: PlayerScreenState): String? {
    val series = state.seriesName?.takeIf { it.isNotBlank() }?.let { name ->
        state.seriesSequence?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.detail_series_band, name, it) } ?: name
    }
    val author = state.author?.takeIf { it.isNotBlank() && it != state.seriesName }
    return listOfNotNull(author, series).joinToString(" · ").ifEmpty { null }
}

/**
 * [header], a square [cover] and [body] stacked; the cover gets the height that is left of [viewportHeight] (px),
 * between [minCover] and [maxCover] and never wider than the width. Space left over goes around the cover. When even
 * the smallest cover does not fit, the column is taller than the viewport (the caller scrolls).
 */
@Composable
private fun CoverFitColumn(
    viewportHeight: Int,
    minCover: Dp,
    maxCover: Dp,
    header: @Composable () -> Unit,
    cover: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
) {
    Layout(contents = listOf(header, cover, body), modifier = modifier) { (headerParts, coverParts, bodyParts), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val head = headerParts.map { it.measure(loose) }
        val rest = bodyParts.map { it.measure(loose) }
        val fixed = head.sumOf { it.height } + rest.sumOf { it.height } + 2 * gap.roundToPx()
        val largest = minOf(maxCover.roundToPx(), width)
        val side = (viewportHeight - fixed).coerceIn(minOf(minCover.roundToPx(), largest), largest)
        val pic = coverParts.map { it.measure(Constraints.fixed(side, side)) }
        val content = fixed + side
        val height = maxOf(content, viewportHeight)
        val extra = height - content
        layout(width, height) {
            var y = 0
            head.forEach { it.place(0, y); y += it.height }
            y += gap.roundToPx() + extra / 2
            pic.forEach { it.place((width - side) / 2, y) }
            y += side + gap.roundToPx() + (extra - extra / 2)
            rest.forEach { it.place(0, y); y += it.height }
        }
    }
}

@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.player_speed_title), style = MaterialTheme.typography.titleMedium)
        Text(formatSpeed(speed), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp).testTag("speedValue"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayerViewModel.PRESETS.forEach { p ->
                FilterChip(selected = speed == p, onClick = { onSpeed(p) }, label = { Text(formatSpeed(p)) }, modifier = Modifier.testTag("preset$p"))
            }
        }
        val steps = ((PlayerViewModel.MAX_SPEED - PlayerViewModel.MIN_SPEED) / 0.05f).roundToInt() - 1
        Slider(
            value = speed,
            onValueChange = onSpeed,
            valueRange = PlayerViewModel.MIN_SPEED..PlayerViewModel.MAX_SPEED,
            steps = steps,
            modifier = Modifier.testTag("speedSlider"),
        )
    }
}
