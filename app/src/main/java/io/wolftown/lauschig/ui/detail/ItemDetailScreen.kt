package io.wolftown.lauschig.ui.detail

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import io.wolftown.lauschig.db.entities.BookmarkEntity
import io.wolftown.lauschig.history.History
import io.wolftown.lauschig.ui.extras.BookmarkNoteDialog
import io.wolftown.lauschig.ui.extras.TitleExtrasViewModel
import io.wolftown.lauschig.ui.extras.titleExtrasSections
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.admin.ItemAdminMenu
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.ui.nav.BrowseKind
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import io.wolftown.lauschig.ui.adaptive.LocalReadableGutter
import io.wolftown.lauschig.ui.theme.animateCoverTint
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import io.wolftown.lauschig.ui.common.LocalOfflineDimming
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel

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
    val tint = animateCoverTint(model, surface)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // The tint reaches under the status bar and, on a wide window that centres this page, into the free sides.
    val reachTop = WindowInsets.systemBars.getTop(density).toFloat()
    val reachSide = with(density) { LocalReadableGutter.current.toPx() }
    // The window (or pane) this page has, not the page's own height: the list below scrolls and has no bounds.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideCover = if (headerBesideCover(maxWidth, maxHeight)) sideCoverSize(maxWidth, maxHeight) else null
        val coverPx = with(density) { (sideCover ?: minOf(COLUMN_COVER_MAX, maxWidth - 32.dp)).toPx() }
        LazyColumn(
            Modifier.fillMaxSize().drawBehind {
                drawHeaderTint(listState, tint.value, surface, coverPx, besideCover = sideCover != null, reachTop, reachSide)
            },
            state = listState,
        ) {
            item("back") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            item("header") { Header(state, sideCover, onPrimary, onDownload, onPause, onDeleteDownload, onOpenBrowse) }
            item("finished") { Box(Modifier.padding(horizontal = 16.dp)) { finished() } }
            extras()
            item("chapters-title") {
                Text(
                    stringResource(R.string.detail_chapters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            if (state.chapters.isEmpty() && !state.detailsLoaded && settledOffline) {
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

/**
 * The header's backdrop, moving with the list: the cover's [tint] behind the back row and the cover, fading into the
 * [surface] before the texts start – downwards to just below the cover in the column, sideways to just past the cover
 * (and softly at its bottom) when the cover sits beside the texts. Nothing once the header has scrolled away.
 */
private fun DrawScope.drawHeaderTint(
    list: LazyListState, tint: Color, surface: Color, coverPx: Float, besideCover: Boolean, reachTop: Float, reachSide: Float,
) {
    if (tint == surface) return
    val items = list.layoutInfo.visibleItemsInfo
    val header = items.firstOrNull { it.key == "header" } ?: return
    val pageTop = (items.firstOrNull { it.key == "back" }?.offset ?: (header.offset - 48.dp.roundToPx())).toFloat()
    val coverBottom = header.offset + coverPx
    if (!besideCover) {
        val end = coverBottom + 8.dp.toPx()
        if (end <= 0f) return
        drawRect(
            Brush.verticalGradient(0f to tint, 1f to surface, startY = pageTop - reachTop, endY = end),
            topLeft = Offset(-reachSide, -reachTop),
            size = Size(size.width + 2 * reachSide, end + reachTop),
        )
    } else {
        val fade = 32.dp.toPx()
        if (coverBottom + fade <= 0f) return
        val endX = 16.dp.toPx() + coverPx + 8.dp.toPx()
        val start = if (layoutDirection == LayoutDirection.Rtl) size.width + reachSide else -reachSide
        val stop = if (layoutDirection == LayoutDirection.Rtl) size.width - endX else endX
        val left = minOf(start, stop)
        val width = kotlin.math.abs(stop - start)
        drawRect(
            Brush.horizontalGradient(0f to tint, 1f to surface, startX = start, endX = stop),
            topLeft = Offset(left, -reachTop),
            size = Size(width, coverBottom + reachTop),
        )
        // The column's foot fades into the surface instead of ending in a line.
        drawRect(
            Brush.verticalGradient(0f to surface.copy(alpha = 0f), 1f to surface, startY = coverBottom - fade, endY = coverBottom),
            topLeft = Offset(left, coverBottom - fade),
            size = Size(width, fade),
        )
    }
}

/** [sideCover]: the cover's size when it sits beside the texts (see [headerBesideCover]); null puts it above them. */
@Composable
private fun Header(state: ItemDetailUiState, sideCover: Dp?, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (sideCover == null) {
            Cover(state)
            HeaderTexts(state, titleTop = 16.dp, onPrimary, onDownload, onPause, onDelete, onOpenBrowse)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Cover(state, sideCover)
                // The primary action and the download controls stay with the title, so they are above the fold.
                Column(Modifier.weight(1f)) { HeaderTexts(state, titleTop = 0.dp, onPrimary, onDownload, onPause, onDelete, onOpenBrowse) }
            }
        }
        state.description?.let { Description(it) }
    }
}

@Composable
private fun HeaderTexts(state: ItemDetailUiState, titleTop: Dp, onPrimary: () -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onOpenBrowse: (BrowseKind, String) -> Unit) {
    Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth().padding(top = titleTop))
    state.subtitle?.let {
        Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
    Chips(state, onOpenBrowse)
    PrimaryButton(state, onPrimary)
    if (state.isLocal) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("localSource")) { Hint(stringResource(R.string.detail_local_source)) }
    } else {
        DownloadControls(state, onDownload, onPause, onDelete)
    }
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

/** One chip per author and narrator, the series and each genre; all open the matching entity page. */
@Composable
private fun Chips(state: ItemDetailUiState, onOpenBrowse: (BrowseKind, String) -> Unit) {
    val seriesLabel = state.seriesName?.let { name ->
        state.seriesSequence?.let { stringResource(R.string.detail_series_band, name, it) } ?: name
    }
    val specs = buildList {
        people.splitPeople(state.author).forEach { add(ChipSpec(it, BrowseKind.AUTHOR to it)) }
        people.splitPeople(state.narrator).forEach { add(ChipSpec(it, BrowseKind.NARRATOR to it)) }
        state.seriesName?.let { add(ChipSpec(seriesLabel ?: it, BrowseKind.SERIES to it)) }
        state.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { add(ChipSpec(it, BrowseKind.GENRE to it)) }
    }
    if (specs.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
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

@Composable
private fun PrimaryButton(state: ItemDetailUiState, onPrimary: () -> Unit) {
    val label = when (val p = state.primary) {
        PrimaryAction.Play -> stringResource(R.string.detail_play)
        is PrimaryAction.Continue ->
            if (p.chapterNumber != null) stringResource(R.string.detail_continue_chapter, p.chapterNumber) else stringResource(R.string.detail_continue)
        PrimaryAction.ListenAgain -> stringResource(R.string.detail_listen_again)
    }
    val settledOffline = LocalOfflineDimming.current
    val playEnabled = state.playableOffline || !settledOffline
    Button(onClick = onPrimary, enabled = playEnabled, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(label) }
    if (!playEnabled) {
        Text(stringResource(R.string.error_not_downloaded_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun DownloadControls(state: ItemDetailUiState, onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit) {
    // What the confirm dialog is for; null = hidden. Both removal flavours reuse the same dialog.
    var confirm by rememberSaveable { mutableStateOf(false) }
    // Starting, resuming and retrying need the server; removing never does.
    val canStart = !LocalOfflineDimming.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val d = state.download) {
            DownloadUi.None -> {
                OutlinedButton(onClick = onDownload, enabled = canStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detail_download)) }
            }
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
                    TextButton(onClick = { if (d.hasPartialData) confirm = true else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
                }
            }
            is DownloadUi.Paused -> {
                Text(stringResource(R.string.detail_download_paused), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDownload, enabled = canStart) { Text(stringResource(R.string.detail_download_resume)) }
                    TextButton(onClick = { if (d.hasPartialData) confirm = true else onDelete() }) { Text(stringResource(R.string.detail_download_cancel)) }
                }
            }
            is DownloadUi.Complete -> {
                Text(
                    stringResource(R.string.detail_downloaded, Formatter.formatShortFileSize(LocalContext.current, d.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.detail_download_delete)) }
            }
            is DownloadUi.Failed -> {
                Text(stringResource(d.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDownload, enabled = canStart) { Text(stringResource(R.string.detail_download_retry)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.detail_download_remove)) }
                }
            }
        }
        if (!canStart && state.download.needsServer()) Hint(stringResource(R.string.detail_download_offline))
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
