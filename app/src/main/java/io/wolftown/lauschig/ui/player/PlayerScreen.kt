package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.PlayableKey
import io.wolftown.lauschig.ui.common.DownloadIconButton
import io.wolftown.lauschig.ui.common.FinishedCard
import io.wolftown.lauschig.ui.common.ProgressMenuButton
import io.wolftown.lauschig.ui.common.Initials
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
            PlayerExtrasRow(onBookmarks = { sheet = PlayerSheet.Bookmarks }, onQueue = { sheet = PlayerSheet.Queue }, onHistory = { sheet = PlayerSheet.History })
        },
        comfort = {
            ComfortRow(
                comfort,
                onStartSleep = comfortViewModel::startSleep,
                onCancelSleep = comfortViewModel::cancelSleep,
                onSkipSilence = comfortViewModel::setSkipSilence,
                onBoost = comfortViewModel::setBoost,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        },
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

/** Bookmarks, queue and listening history of the player. */
@Composable
fun PlayerExtrasRow(onBookmarks: () -> Unit, onQueue: () -> Unit, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        OutlinedButton(onClick = onBookmarks, modifier = Modifier.testTag("bookmarksButton")) {
            Icon(Icons.Rounded.Bookmarks, null, Modifier.size(18.dp))
            Text(stringResource(R.string.player_bookmarks), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onQueue, modifier = Modifier.testTag("queueButton")) {
            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(18.dp))
            Text(stringResource(R.string.player_queue), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onHistory, modifier = Modifier.testTag("historyButton")) {
            Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
            Text(stringResource(R.string.player_history), Modifier.padding(start = 8.dp))
        }
    }
}

/** "1,25×" in the user's locale. */
fun formatSpeed(speed: Float): String = speedFormat().format(speed) + "×"

private val speedFormats = java.util.concurrent.ConcurrentHashMap<java.util.Locale, NumberFormat>()

/** One cached [NumberFormat] per default locale (formats are not thread-safe, but only the UI thread uses this). */
private fun speedFormat(): NumberFormat = speedFormats.getOrPut(java.util.Locale.getDefault()) {
    NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 2 }
}

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
    /** Sleep timer and audio options ([ComfortRow]); shown only while a title is loaded. */
    comfort: @Composable () -> Unit = {},
    /** Bookmarks, queue, history ([PlayerExtrasRow]); shown only while a title is loaded. */
    extras: @Composable () -> Unit = {},
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

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        val detailsLabel = stringResource(R.string.player_open_details)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.player_close)) }
            Spacer(Modifier.weight(1f))
            if (state.itemId != null) {
                DownloadIconButton(download, onDownload = onDownload, onDelete = onDeleteDownload)
                IconButton(onClick = onOpenDetails, modifier = Modifier.testTag("playerDetails")) { Icon(Icons.Rounded.Info, detailsLabel) }
                topActions()
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val model = coverModel(LocalServerBaseUrl.current, state.itemId.orEmpty(), state.coverPath, 0)
            Box(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = detailsLabel, onClick = onOpenDetails).testTag("playerCover"),
            ) {
                Initials(state.title)
                if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClickLabel = detailsLabel, onClick = onOpenDetails),
        )
        if (state.itemId != null) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) { SourceIndicator(state.isStreaming) }
        }
        state.chapterTitle?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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

        val sliderLabel = stringResource(R.string.player_chapter_slider)
        Slider(
            value = dragging ?: pending ?: state.chapterProgress,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeekInChapter(it); pending = it }; dragging = null },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp).semantics { contentDescription = sliderLabel }.testTag("chapterSlider"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownElapsed = dragging?.let { io.wolftown.lauschig.ui.detail.formatClock(it * state.chapterDurationSec) } ?: state.chapterElapsedText
            Text(shownElapsed, style = MaterialTheme.typography.labelLarge)
            Text(state.chapterTotalText, style = MaterialTheme.typography.labelLarge)
        }
        Text(
            stringResource(R.string.player_book_time, state.bookElapsedText, state.bookTotalText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("bookTime"),
        )

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
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

        finished()
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val speedDescription = stringResource(R.string.player_speed_button, formatSpeed(state.speed))
            OutlinedButton(onClick = { showSpeed = true }, modifier = Modifier.testTag("speedButton").semantics { contentDescription = speedDescription }) {
                Text(formatSpeed(state.speed))
            }
            OutlinedButton(onClick = { showChapters = true }, enabled = hasChapters, modifier = Modifier.testTag("chaptersButton")) {
                Icon(Icons.AutoMirrored.Rounded.List, null, Modifier.size(18.dp))
                Text(stringResource(R.string.player_chapters), Modifier.padding(start = 8.dp))
            }
        }
        if (state.itemId != null) comfort()
        if (state.itemId != null) extras()
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
