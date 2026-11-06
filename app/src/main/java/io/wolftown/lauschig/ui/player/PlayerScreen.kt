package io.wolftown.lauschig.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wolftown.lauschig.R
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.settings.RemainingTime
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.CoverShapes
import io.wolftown.lauschig.ui.common.DownloadIconButton
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.ItemActionsViewModel
import io.wolftown.lauschig.ui.common.LocalBackdropReach
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import io.wolftown.lauschig.ui.common.QuickDownload
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.detail.rememberNotificationPermissionOnce
import io.wolftown.lauschig.ui.extras.BookmarksContent
import io.wolftown.lauschig.ui.extras.HistoryContent
import io.wolftown.lauschig.ui.extras.QueueContent
import io.wolftown.lauschig.ui.theme.TINT_FADE_MS
import io.wolftown.lauschig.ui.theme.controlColorOn
import io.wolftown.lauschig.ui.theme.coverTintTarget
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
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
    PlayerScreen(
        state, onClose, modifier,
        // The player's id is the playable key; details open on the library item (the podcast for an episode).
        onOpenDetails = { itemId?.let { onOpenDetails(PlayableKey.parse(it).libraryItemId) } },
        download = download,
        onDownload = { itemId?.let { actions.download(it); askNotifications() } },
        onDeleteDownload = { itemId?.let(actions::deleteDownload) },
        topActions = { itemId?.let { ProgressMenuButton(it, viewModel = actions) } },
        castControl = { CastControl(itemId) },
        finished = { itemId?.let { FinishedCard(it, { id -> onOpenDetails(PlayableKey.parse(id).libraryItemId) }, Modifier.padding(vertical = 8.dp), viewModel = actions) } },
        onToggle = viewModel::togglePlayPause,
        onSeekBy = viewModel::seekBy,
        onSeekInChapter = viewModel::seekInChapter,
        onPrevious = viewModel::previousChapter,
        onNext = viewModel::nextChapter,
        onChapter = viewModel::seekToChapter,
        onSpeed = viewModel::setSpeed,
        onToggleRemaining = viewModel::toggleRemainingTime,
        contents = PlayerContents(
            queue = { done -> QueueContent(done) },
            bookmarks = { done -> itemId?.let { BookmarksContent(it, state.chapterStartSec + state.chapterPositionSec, done) } },
            history = { done -> itemId?.let { HistoryContent(it, done) } },
        ),
        comfort = {
            ComfortButtons(
                comfort,
                onStartSleep = comfortViewModel::startSleep,
                onCancelSleep = comfortViewModel::cancelSleep,
                onSkipSilence = comfortViewModel::setSkipSilence,
                onBoost = comfortViewModel::setBoost,
                onSleepChime = comfortViewModel::setSleepChime,
                onCompressor = comfortViewModel::setCompressor,
            )
        },
        comfortHint = { ShakeHint(comfort) },
    )
}

/**
 * A time left on the clock, rounded up to the second (the elapsed time is rounded down), so the two add up to the length;
 * a few ms over a full second are float noise, not one second more.
 */
internal fun remainingClock(sec: Double): String = io.wolftown.lauschig.ui.detail.formatClock(ceil(sec - 0.001).coerceAtLeast(0.0))

/** A time span as TalkBack should say it: "3 Stunden 18 Minuten", "14 Minuten", "40 Sekunden". */
@Composable
internal fun spokenDuration(sec: Double): String {
    val total = sec.coerceAtLeast(0.0).toLong()
    val h = (total / 3600).toInt()
    val m = (total % 3600 / 60).toInt()
    return when {
        h > 0 && m > 0 -> pluralStringResource(R.plurals.spoken_hours, h, h) + " " + pluralStringResource(R.plurals.spoken_minutes, m, m)
        h > 0 -> pluralStringResource(R.plurals.spoken_hours, h, h)
        m > 0 -> pluralStringResource(R.plurals.spoken_minutes, m, m)
        else -> pluralStringResource(R.plurals.spoken_seconds, total.toInt(), total.toInt())
    }
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
    /** The Cast button ([CastControl]); it also starts Cast when the player opens, so it is there with or without a title. */
    castControl: @Composable () -> Unit = {},
    finished: @Composable () -> Unit = {},
    /** The Sleep and Klang tiles ([ComfortButtons]) beside Tempo; shown only while a title is loaded. */
    comfort: @Composable RowScope.() -> Unit = {},
    /** Under the tiles, e.g. "shake to extend" ([ShakeHint]); shown only while a title is loaded. */
    comfortHint: @Composable () -> Unit = {},
    /** Queue, bookmarks and history tabs of the contents sheet (the chapters are the player's own). */
    contents: PlayerContents = PlayerContents(),
    /** The right timestamp was tapped: switch between the chapter's and the book's time left. */
    onToggleRemaining: () -> Unit = {},
) {
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var contentsTab by rememberSaveable { mutableStateOf<ContentsTab?>(null) }
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
    // A server cover only once this title's version is known, so it is not requested twice.
    val art = state.itemId?.let { id ->
        if (state.coverPath == null && state.coverVersion == null) null
        else coverModel(LocalServerBaseUrl.current, id, state.coverPath, state.coverVersion ?: 0)
    }
    val surface = MaterialTheme.colorScheme.surface
    // The signature: the cover's tint at the top, fading into the surface at 60 % of the height.
    val scheme = MaterialTheme.colorScheme
    val tintTarget = coverTintTarget(art, state.coverVersion ?: 0, surface)
    val tint = animateColorAsState(tintTarget, tween(TINT_FADE_MS), label = "playerTint")
    // The controls (play button, slider, "downloaded") stay primary only where primary keeps 3:1 on the tint at the top.
    val playerScheme = remember(scheme, tintTarget) {
        if (scheme.controlColorOn(tintTarget) == scheme.primary) scheme else scheme.copy(primary = scheme.onSurface, onPrimary = scheme.surface)
    }
    val tabular = remember { TextStyle(fontFeatureSettings = "tnum") }

    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_close)) }
            // Where it plays from sits in the top bar's free middle, which keeps the title block short.
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                // On a Cast device neither stream nor download is what plays; the line under the title says where it plays.
                if (state.itemId != null && state.castDevice == null) SourceIndicator(playSource(state.itemId, state.isStreaming))
            }
            castControl()
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
            state.castDevice?.let { CastOutputLine(it) }
            state.error?.let {
                Text(
                    stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("playerError"),
                )
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
            // Two timestamps: the chapter's elapsed time on the left, the time left on the right – of the chapter or of
            // the whole book, a tap switches (and is kept). Without chapters both are the book's: no word, no switch. Both
            // follow the thumb while it is dragged and show the target until the seek lands.
            val targetSec = (dragging ?: pending)?.let { it * state.chapterDurationSec }
            val elapsed = targetSec?.let { io.wolftown.lauschig.ui.detail.formatClock(it) } ?: state.chapterElapsedText
            val ahead = targetSec?.let { it - state.chapterPositionSec } ?: 0.0
            val split = state.splitByChapters
            val book = !split || state.remainingTime == RemainingTime.BOOK
            val left = ((if (book) state.bookRemainingSec else state.chapterRemainingSec) - ahead).coerceAtLeast(0.0)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(elapsed, style = MaterialTheme.typography.labelLarge.merge(tabular), modifier = Modifier.testTag("elapsed"))
                Spacer(Modifier.weight(1f))
                val description = stringResource(
                    when {
                        !split -> R.string.player_remaining_description
                        book -> R.string.player_remaining_book_description
                        else -> R.string.player_remaining_chapter_description
                    },
                    spokenDuration(left),
                )
                val toggleLabel = stringResource(if (book) R.string.player_remaining_show_chapter else R.string.player_remaining_show_book)
                Row(
                    Modifier
                        .clip(MaterialTheme.shapes.small)
                        .then(if (split) Modifier.clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggleRemaining) else Modifier)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .heightIn(min = 40.dp)
                        .padding(start = 8.dp)
                        .testTag("remaining"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (split) {
                        Text(
                            stringResource(if (book) R.string.player_remaining_book_short else R.string.player_remaining_chapter_short),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Text("−" + remainingClock(left), style = MaterialTheme.typography.labelLarge.merge(tabular))
                }
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = hasChapters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_previous_chapter), Modifier.size(32.dp))
            }
            val back = pluralStringResource(R.plurals.player_seek_back, state.seekBackSec, state.seekBackSec)
            IconButton(onClick = { onSeekBy(-state.seekBackSec) }, modifier = Modifier.size(56.dp).semantics { contentDescription = back }) {
                SeekIcon(state.seekBackSec, forward = false, size = 36.dp)
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
            val forward = pluralStringResource(R.plurals.player_seek_forward, state.seekForwardSec, state.seekForwardSec)
            IconButton(onClick = { onSeekBy(state.seekForwardSec) }, modifier = Modifier.size(56.dp).semantics { contentDescription = forward }) {
                SeekIcon(state.seekForwardSec, forward = true, size = 36.dp)
            }
            IconButton(onClick = onNext, enabled = hasChapters, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next_chapter), Modifier.size(32.dp))
            }
        }
    }
    // Variant B: three equal tiles (Tempo, Sleep, Klang), then one wide banner for everything inside the title.
    val actions: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(PlayerActionSpacing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PlayerActionSpacing)) {
                val speedDescription = stringResource(R.string.player_speed_button, formatSpeed(state.speed))
                PlayerTile(
                    Icons.Rounded.Speed, stringResource(R.string.player_tile_speed), formatSpeed(state.speed), onClick = { showSpeed = true },
                    active = state.speed != 1f,
                    modifier = Modifier.testTag("speedButton").semantics { contentDescription = speedDescription },
                )
                if (loaded) comfort()
            }
            if (loaded) {
                comfortHint()
                ContentsBanner(state.chapters, onClick = { contentsTab = initialContentsTab(hasChapters) })
            }
        }
    }

    // The gradient reaches under the status bar and the side insets where the frame allows it (never over a banner).
    val reach = LocalBackdropReach.current
    val density = LocalDensity.current
    MaterialTheme(colorScheme = playerScheme) {
        BoxWithConstraints(
            modifier.fillMaxSize().drawBehind {
                val top = with(density) { reach.top.toPx() }
                val left = with(density) { reach.left.toPx() }
                val right = with(density) { reach.right.toPx() }
                drawRect(
                    Brush.verticalGradient(0f to tint.value, 0.6f to surface, startY = -top, endY = size.height),
                    topLeft = Offset(-left, -top),
                    size = Size(size.width + left + right, size.height + top),
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
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) { SpeedSheet(state.speed, onSpeed, casting = state.castDevice != null) }
    }
    contentsTab?.let { tab ->
        ContentsSheet(tab, state.chapters, onChapter, contents, onDismiss = { contentsTab = null }, titleKey = state.itemId)
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
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, casting: Boolean) {
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
        if (casting) {
            Text(
                stringResource(R.string.cast_speed_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("castSpeedLimit"),
            )
        }
    }
}
