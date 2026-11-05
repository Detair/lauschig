package io.wolftown.lauschig.ui.detail

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.admin.ItemAdminMenu
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.history.History
import io.wolftown.lauschig.ui.adaptive.LocalReadableGutter
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.LocalBackdropReach
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.OfflineIcon
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.extras.BookmarkNoteDialog
import io.wolftown.lauschig.ui.extras.TitleExtrasViewModel
import io.wolftown.lauschig.ui.extras.listenedText
import io.wolftown.lauschig.ui.extras.titleExtrasSections
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.theme.animateCoverTint

@Composable
fun ItemDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenItem: (itemId: String) -> Unit = {},
    onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
    viewModel: ItemDetailViewModel = hiltViewModel(),
    extrasViewModel: TitleExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askNotifications = rememberNotificationPermissionOnce()
    val key = state.itemId
    LaunchedEffect(key) { if (key.isNotEmpty()) extrasViewModel.refreshHistory(key) }
    val bookmarks by remember(key) { extrasViewModel.bookmarks(key) }.collectAsStateWithLifecycle(emptyList())
    val history by remember(key) { extrasViewModel.history(key) }.collectAsStateWithLifecycle(History(emptyList(), 0.0))
    val online by extrasViewModel.online.collectAsStateWithLifecycle(true)
    var allHistory by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    val context = LocalContext.current
    ItemDetailScreen(
        state, onBack, viewModel::onPrimary, { viewModel.download(); askNotifications() }, viewModel::pauseDownload,
        viewModel::deleteDownload, viewModel::onChapter, modifier,
        topActions = {
            // Admins: rescan / re-match this title on the server (hidden for everyone else and for device titles).
            if (state.found) ItemAdminMenu(state.title)
            if (state.found) {
                ProgressMenuButton(state.itemId, onEnqueue = {
                    extrasViewModel.enqueue(key)
                    Toast.makeText(context, R.string.queue_added, Toast.LENGTH_SHORT).show()
                })
            }
        },
        finished = { if (state.found) FinishedCard(state.itemId, onOpenItem, Modifier.padding(top = 16.dp)) },
        onOpenBrowse = onOpenBrowse,
        extras = {
            titleExtrasSections(
                key, bookmarks, history, allHistory, online,
                onShowAllHistory = { allHistory = true },
                onJump = { extrasViewModel.jumpTo(key, it) },
                onEditBookmark = { editing = it },
                onDeleteBookmark = extrasViewModel::deleteBookmark,
            )
        },
    )
    editing?.let { row ->
        BookmarkNoteDialog(stringResource(R.string.bookmark_edit_title), row.note, onSave = { extrasViewModel.editBookmark(row, it); editing = null }, onDismiss = { editing = null })
    }
}

/** Below this height the cover moves beside the texts (see [headerBesideCover]). */
private val SHORT_WINDOW = 600.dp

/**
 * The row needs this width: with the gutters (32 dp) and the gap (16 dp) the cover then gets 40 % of the content width
 * (147 dp) and the texts about 205 dp; narrower, and the texts would be squeezed below 200 dp.
 */
private val ROW_MIN_WIDTH = 400.dp

/** The cover beside the texts: smaller than above them, at most 40 % of the width and never taller than the window leaves room for. */
private val SIDE_COVER_MAX = 200.dp
private val SIDE_COVER_MIN = 120.dp

/**
 * Whether the header lays the cover out beside title, chips and buttons instead of above them. The column needs about
 * 550-650 dp until the primary button (back row, 280 dp cover, title, chips, button), so below [SHORT_WINDOW] the button
 * lands under the fold: the landscape pane of a tablet (about 455 dp wide, 470 dp high with the mini player) and a phone
 * in landscape (800 x 360 dp). Windows narrower than [ROW_MIN_WIDTH] keep the column, as do portrait phones and tablets
 * (tall enough).
 */
internal fun headerBesideCover(width: Dp, height: Dp): Boolean = width >= ROW_MIN_WIDTH && height < SHORT_WINDOW

/** The cover's size beside the texts in a window of this size. */
internal fun sideCoverSize(width: Dp, height: Dp): Dp =
    minOf(SIDE_COVER_MAX, (width - 32.dp) * 0.4f, height - 96.dp).coerceAtLeast(SIDE_COVER_MIN)

@Composable
fun ItemDetailScreen(
    state: ItemDetailUiState,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDeleteDownload: () -> Unit,
    onChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable () -> Unit = {},
    finished: @Composable () -> Unit = {},
    onOpenBrowse: (BrowseKind, String) -> Unit = { _, _ -> },
    /** Bookmarks and listening history sections (see `titleExtrasSections`). */
    extras: LazyListScope.() -> Unit = {},
) {
    val settledOffline = LocalOfflineDimming.current
    val model = if (state.found) coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, state.coverVersion) else null
    val surface = MaterialTheme.colorScheme.surface
    val tint = animateCoverTint(model, state.coverVersion, surface)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The tint reaches under the status bar where the frame allows it (never over a banner) and, on a wide window that
    // centres this page, into the free sides.
    val reachTop = with(density) { LocalBackdropReach.current.top.toPx() }
    val gutter = LocalReadableGutter.current
    // Where the header's texts start, measured, so the backdrop fades out above them whatever the header's layout.
    val marks = remember { HeaderMarks() }
    // The window (or pane) this page has, not the page's own height: the list below scrolls and has no bounds.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideCover = if (headerBesideCover(maxWidth, maxHeight)) sideCoverSize(maxWidth, maxHeight) else null
        LazyColumn(
            Modifier.fillMaxSize().drawBehind {
                // The page draws beyond its bounds here (under the status bar, into the gutters); the navigation host and
                // the frame do not clip it, see LocalBackdropReach.
                drawHeaderTint(listState, marks, tint.value, surface, reachTop, gutter?.px ?: 0f)
            },
            state = listState,
        ) {
            item("back") {
                Row(Modifier.fillMaxWidth().onSizeChanged { marks.backRowHeight = it.height }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.detail_back)) }
                    Spacer(Modifier.weight(1f))
                    topActions()
                }
            }
            if (!state.found) {
                item("missing") {
                    Text(stringResource(R.string.detail_not_found), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
                return@LazyColumn
            }
            item("header") { Header(state, sideCover, marks, onPrimary, onDownload, onPause, onDeleteDownload, onOpenBrowse) }
            item("finished") { Box(Modifier.padding(horizontal = 16.dp)) { finished() } }
            extras()
            val chaptersOffline = state.chapters.isEmpty() && !state.detailsLoaded && settledOffline
            // A title without chapters (one file, e.g. a radio play) gets no heading over nothing.
            if (state.chapters.isNotEmpty() || chaptersOffline) {
                item("chapters-title") {
                    Text(
                        stringResource(R.string.detail_chapters),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    )
                }
            }
            if (chaptersOffline) {
                item("chapters-offline") {
                    Text(
                        stringResource(R.string.detail_chapters_offline_hint),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            chapterItems(state.chapters, onChapter)
        }
    }
}

/** The column's cover: as wide as the window up to this. */
private val COLUMN_COVER_MAX = 280.dp

/** Measured while laying out the header, read while drawing its backdrop. */
@Stable
private class HeaderMarks {
    var backRowHeight by mutableIntStateOf(0)

    /** The title's top inside the header item; NaN until laid out. */
    var titleTop by mutableFloatStateOf(Float.NaN)

    /** The header item's coordinates, to measure the title against (no state: only read in the title's callback). */
    var header: LayoutCoordinates? = null
}

/** How far above the title the backdrop has faded out completely. */
private val TINT_FADE_ABOVE_TITLE = 8.dp

/**
 * The header's backdrop, moving with the list: the cover's [tint] from under the status bar (or from the page's top
 * below a banner, see `LocalBackdropReach`), fading into the [surface] just above the title – below the cover in the
 * column, behind the back row when the cover sits beside the texts. Nothing once the header has scrolled away.
 */
private fun DrawScope.drawHeaderTint(list: LazyListState, marks: HeaderMarks, tint: Color, surface: Color, reachTop: Float, reachSide: Float) {
    if (tint == surface || marks.titleTop.isNaN()) return
    val items = list.layoutInfo.visibleItemsInfo
    val header = items.firstOrNull { it.key == "header" } ?: return
    val pageTop = (items.firstOrNull { it.key == "back" }?.offset ?: (header.offset - marks.backRowHeight)).toFloat()
    val end = header.offset + marks.titleTop - TINT_FADE_ABOVE_TITLE.toPx()
    if (end <= 0f) return
    drawRect(
        Brush.verticalGradient(0f to tint, 1f to surface, startY = pageTop - reachTop, endY = end),
        topLeft = Offset(-reachSide, -reachTop),
        size = Size(size.width + 2 * reachSide, end + reachTop),
    )
}

/** [sideCover]: the cover's size when it sits beside the texts (see [headerBesideCover]); null puts it above them. */
@Composable
private fun Header(state: ItemDetailUiState, sideCover: Dp?, marks: HeaderMarks, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    Column(Modifier.fillMaxWidth().onGloballyPositioned { marks.header = it }.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (sideCover == null) {
            Cover(state)
            HeaderTexts(state, titleTop = 16.dp, marks, onPrimary, onDownload, onPause, onDelete, onOpenBrowse)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(state, sideCover)
                // The primary action and the download controls stay with the title, so they are above the fold.
                Column(Modifier.weight(1f)) { HeaderTexts(state, titleTop = 0.dp, marks, onPrimary, onDownload, onPause, onDelete, onOpenBrowse) }
            }
        }
        state.description?.let { Description(it) }
    }
}

@Composable
private fun HeaderTexts(state: ItemDetailUiState, titleTop: Dp, marks: HeaderMarks, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    Text(
        state.title, style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth().padding(top = titleTop).onGloballyPositioned { title ->
            marks.header?.takeIf { it.isAttached }?.let { marks.titleTop = it.localPositionOf(title, Offset.Zero).y }
        },
    )
    state.subtitle?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
    QuickBar(state, onOpenBrowse)
    Chips(state, onOpenBrowse)
    PlayAndDownload(state, onPrimary, onDownload, onPause, onDelete)
}

/** [side]: the square's size beside the texts; null is the column's cover, as wide as the window up to 280 dp. */
@Composable
private fun Cover(state: ItemDetailUiState, side: Dp? = null) {
    val model = coverModel(LocalServerBaseUrl.current, state.itemId, state.coverPath, state.coverVersion)
    val size = if (side != null) Modifier.size(side) else Modifier.widthIn(max = COLUMN_COVER_MAX).fillMaxWidth().aspectRatio(1f)
    CoverArt(
        model, state.title,
        size.shadow(8.dp, CoverShapes.Tile, ambientColor = CoverShadow, spotColor = CoverShadow).testTag("cover"),
        initialsStyle = MaterialTheme.typography.headlineLarge,
    )
}

private val CoverShadow = Color.Black.copy(alpha = 0.3f)

private class ChipSpec(val label: String, val target: Pair<BrowseKind, String>)

private val people = Catalog()

/**
 * The quick facts under the title: length, narrator(s) and year, each with its symbol; what is unknown is left out
 * (nothing at all: no bar). The narrators are one fact; a tap opens the narrator's page as their chips did (several: a
 * small menu picks one); they are not repeated in the chips.
 * The server does not tell this app whether a title is abridged, so that badge of the plan is not there.
 */
@Composable
private fun QuickBar(state: ItemDetailUiState, onOpenBrowse: (BrowseKind, String) -> Unit) {
    val length = state.durationSec.takeIf { it > 0 }
    val narrators = people.splitPeople(state.narrator)
    val year = state.publishedYear
    if (length == null && narrators.isEmpty() && year == null) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("quickBar"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        length?.let { sec ->
            val text = listenedText(sec)
            QuickFact(Icons.Rounded.Schedule, text, stringResource(R.string.detail_quick_length, text))
        }
        if (narrators.isNotEmpty()) {
            val names = narrators.joinToString(", ")
            var pick by remember { mutableStateOf(false) }
            // A device title's people lead nowhere (entity pages list the server's catalog).
            val open = when {
                state.isLocal -> null
                narrators.size == 1 -> { { onOpenBrowse(BrowseKind.NARRATOR, narrators.single()) } }
                else -> { { pick = true } }
            }
            Box {
                QuickFact(Icons.Rounded.Mic, names, stringResource(R.string.detail_quick_narrator, names), open)
                DropdownMenu(pick, onDismissRequest = { pick = false }) {
                    narrators.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { pick = false; onOpenBrowse(BrowseKind.NARRATOR, name) })
                    }
                }
            }
        }
        year?.let { QuickFact(Icons.Rounded.CalendarToday, it, stringResource(R.string.detail_quick_year, it)) }
    }
}

/** One fact of the [QuickBar]: symbol and value, said as "[description]"; with [onClick] a link (the narrator). */
@Composable
private fun QuickFact(icon: ImageVector, text: String, description: String, onClick: (() -> Unit)? = null) {
    val link = if (onClick != null) {
        // Lined up with the other facts (no inset); the touch target is 48 dp high.
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        link.semantics(mergeDescendants = true) { contentDescription = description }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One chip per author, the series and each genre; all open the matching entity page. The narrator is in the [QuickBar]. */
@Composable
private fun Chips(state: ItemDetailUiState, onOpenBrowse: (BrowseKind, String) -> Unit) {
    val seriesLabel = state.seriesName?.let { name ->
        state.seriesSequence?.let { stringResource(R.string.detail_series_band, name, it) } ?: name
    }
    val specs = buildList {
        people.splitPeople(state.author).forEach { add(ChipSpec(it, BrowseKind.AUTHOR to it)) }
        state.seriesName?.let { add(ChipSpec(seriesLabel ?: it, BrowseKind.SERIES to it)) }
        state.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { add(ChipSpec(it, BrowseKind.GENRE to it)) }
    }
    if (specs.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Entity pages list the server's catalog: a device title's people and genres are shown, but lead nowhere.
        specs.forEach { Chip(it.label, enabled = !state.isLocal) { onOpenBrowse(it.target.first, it.target.second) } }
    }
}

@Composable
private fun Chip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The actions: a large primary button (play, continue, listen again) and beside it a compact download button showing
 * the download's state; below them what the download is doing in words, with its finer controls. A device title has no
 * download, only its hint.
 */
@Composable
private fun PlayAndDownload(state: ItemDetailUiState, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    // The round button on a waiting download: right beside Play, so a stray thumb must not cancel it unasked.
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    // That question belongs to the waiting download: once it runs, finishes or is gone, the question is closed (and a
    // confirm arriving late cancels nothing – never a finished download without its own question).
    val queued = state.download == DownloadUi.Queued
    val download by rememberUpdatedState(state.download)
    LaunchedEffect(queued) { if (!queued) confirmCancel = false }
    val settledOffline = LocalOfflineDimming.current
    val playEnabled = state.playableOffline || !settledOffline
    // Starting, resuming and retrying need the server; removing never does.
    val canStart = !settledOffline
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(state, playEnabled, onPrimary, Modifier.weight(1f))
        if (!state.isLocal) {
            DownloadCompanion(state.download, canStart, onDownload, onPause, onConfirmCancel = { confirmCancel = true }, onConfirmDelete = { confirm = true })
        }
    }
    if (!playEnabled) {
        Text(stringResource(R.string.error_not_downloaded_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    if (state.isLocal) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("localSource")) { Hint(stringResource(R.string.detail_local_source)) }
    } else {
        DownloadStatus(state.download, canStart, onPause, onDelete, onConfirmDelete = { confirm = true })
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text(stringResource(R.string.detail_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.detail_delete_dismiss)) } },
        )
    }
    if (confirmCancel && queued) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.detail_queued_cancel_title)) },
            text = { Text(stringResource(R.string.detail_queued_cancel_message)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; if (download == DownloadUi.Queued) onDelete() }) {
                    Text(stringResource(R.string.detail_queued_cancel_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.detail_queued_cancel_dismiss)) } },
        )
    }
}

/** The height of the primary button and the size of the download button beside it: easy to hit. */
private val ACTION_SIZE = 56.dp

@Composable
private fun PrimaryButton(state: ItemDetailUiState, enabled: Boolean, onPrimary: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (val p = state.primary) {
        PrimaryAction.Play -> stringResource(R.string.detail_play)
        is PrimaryAction.Continue ->
            if (p.chapterNumber != null) stringResource(R.string.detail_continue_chapter, p.chapterNumber) else stringResource(R.string.detail_continue)
        PrimaryAction.ListenAgain -> stringResource(R.string.detail_listen_again)
    }
    Button(onClick = onPrimary, enabled = enabled, modifier = modifier.heightIn(min = ACTION_SIZE).testTag("primaryButton")) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The compact download button beside the primary one, a tonal circle that shows the state and says what a tap does
 * (TalkBack: "double-tap to …"): start, the progress ring (a tap pauses – harmless, it resumes), resume, waiting (a tap
 * asks before cancelling), on this device (a tap asks before deleting), failed (a tap retries).
 */
@Composable
private fun DownloadCompanion(
    d: DownloadUi, canStart: Boolean, onDownload: () -> Unit, onPause: () -> Unit, onConfirmCancel: () -> Unit, onConfirmDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = IconButtonDefaults.filledTonalIconButtonColors()
    @Composable
    fun Companion(description: String, action: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) =
        Box(
            Modifier
                .size(ACTION_SIZE)
                .clip(CircleShape)
                .background(if (enabled) colors.containerColor else colors.disabledContainerColor)
                .clickable(enabled = enabled, onClickLabel = action, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag("downloadCompanion"),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor, content = content)
        }
    when (d) {
        DownloadUi.None -> Companion(stringResource(R.string.detail_download), stringResource(R.string.detail_download), onDownload, canStart) {
            Icon(Icons.Rounded.Download, null)
        }
        DownloadUi.Queued -> Companion(stringResource(R.string.detail_download_queued), stringResource(R.string.detail_download_cancel), onConfirmCancel) {
            CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
            Icon(Icons.Rounded.Downloading, null, Modifier.size(20.dp))
        }
        is DownloadUi.Running -> Companion(stringResource(R.string.detail_downloading, d.percent), stringResource(R.string.detail_download_pause_action), onPause) {
            CircularProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
            Icon(Icons.Rounded.Downloading, null, Modifier.size(20.dp))
        }
        is DownloadUi.Paused -> Companion(stringResource(R.string.detail_download_paused), stringResource(R.string.detail_download_resume_action), onDownload, canStart) {
            Icon(Icons.Rounded.Download, null)
        }
        is DownloadUi.Complete -> Companion(stringResource(R.string.cover_offline_available), stringResource(R.string.detail_download_delete), onConfirmDelete) {
            Icon(OfflineIcon, null, tint = scheme.primary)
        }
        is DownloadUi.Failed -> Companion(stringResource(R.string.detail_download_retry), stringResource(R.string.detail_download_retry), onDownload, canStart) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = if (canStart) scheme.error else LocalContentColor.current)
        }
    }
}

/** What the download is doing, in words, with its finer controls (pause, cancel, delete, remove); nothing before it starts. */
@Composable
private fun DownloadStatus(d: DownloadUi, canStart: Boolean, onPause: () -> Unit, onDelete: () -> Unit, onConfirmDelete: () -> Unit) {
    val offlineHint = !canStart && d.needsServer()
    if (d == DownloadUi.None && !offlineHint) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (d) {
            DownloadUi.None -> Unit
            DownloadUi.Queued -> {
                Text(stringResource(R.string.detail_download_queued), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = onDelete) { Text(stringResource(R.string.detail_download_cancel)) }
            }
            is DownloadUi.Running -> {
                Text(stringResource(R.string.detail_downloading, d.percent), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPause) { Text(stringResource(R.string.detail_download_pause)) }
                    TextButton(onClick = { if (d.hasPartialData) onConfirmDelete() else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
                }
            }
            is DownloadUi.Paused -> {
                Text(stringResource(R.string.detail_download_paused), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { if (d.hasPartialData) onConfirmDelete() else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
            }
            // Text and button side by side where they fit, else the button below: the line itself never breaks (the
            // size keeps number, unit and bracket together; the string has a non-breaking space before the bracket).
            is DownloadUi.Complete -> FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                val size = Formatter.formatShortFileSize(LocalContext.current, d.bytes).replace(' ', '\u00A0')
                Text(stringResource(R.string.detail_downloaded, size), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.detail_download_delete)) }
            }
            is DownloadUi.Failed -> {
                Text(stringResource(d.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDelete) { Text(stringResource(R.string.detail_download_remove)) }
            }
        }
        if (offlineHint) Hint(stringResource(R.string.detail_download_offline))
    }
}

private fun DownloadUi.needsServer() = this is DownloadUi.None || this is DownloadUi.Paused || this is DownloadUi.Failed

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.detail_description_less else R.string.detail_description_more))
            }
        }
    }
}
