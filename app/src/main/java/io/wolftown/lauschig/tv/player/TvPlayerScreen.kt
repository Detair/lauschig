package io.wolftown.lauschig.tv.player

import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.wolftown.lauschig.R
import io.wolftown.lauschig.playback.SleepMode
import io.wolftown.lauschig.playback.SleepTimerUi
import io.wolftown.lauschig.tv.TvDimens
import io.wolftown.lauschig.tv.TvSettingsStore
import io.wolftown.lauschig.tv.browse.ChapterPanel
import io.wolftown.lauschig.tv.common.TvMessage
import io.wolftown.lauschig.tv.common.requestFocusWhenPlaced
import io.wolftown.lauschig.tv.rememberTvSettings
import io.wolftown.lauschig.ui.common.CoverArt
import io.wolftown.lauschig.ui.common.LocalServerBaseUrl
import io.wolftown.lauschig.ui.common.coverModel
import io.wolftown.lauschig.ui.detail.formatClock
import io.wolftown.lauschig.ui.player.ComfortViewModel
import io.wolftown.lauschig.ui.player.PlayerScreenState
import io.wolftown.lauschig.ui.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val TAG_STAGE = "tvPlayerStage"
internal const val TAG_SCREEN_OFF = "tvPlayerScreenOff"
internal const val TAG_EXTEND_HINT = "tvPlayerExtendHint"
internal const val TAG_PROGRESS = "tvPlayerProgress"

/** Not playing for this long is a pause, which brings the picture back; shorter is buffering. */
internal const val PAUSE_WAKES_MS = 3_000L

private const val FOCUS_SETTLE_FRAMES = 3

/** Held left or right seeks at most this often: five times a second, not with every repeat of the remote (review focus 4). */
internal const val SEEK_REPEAT_MS = 200L

/**
 * Keys the system needs whether the picture is on or off: the volume, and the media keys, which go to the media session. While
 * the screen is off they wake it too, but are not swallowed.
 */
private val SYSTEM_KEYS = setOf(
    Key.VolumeUp, Key.VolumeDown, Key.VolumeMute, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause, Key.MediaStop,
    Key.MediaFastForward, Key.MediaRewind, Key.MediaNext, Key.MediaPrevious,
)

/** The full-screen player ([io.wolftown.lauschig.tv.nav.TvPlayer]) with the phone's view models. Back leaves it. */
@Composable
fun TvPlayerRoute(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
    comfort: ComfortViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val comfortState by comfort.state.collectAsStateWithLifecycle()
    val settings = rememberTvSettings()
    val screenOffMinutes by settings.screenOffMinutes.collectAsStateWithLifecycle(TvSettingsStore.DEFAULT_SCREEN_OFF_MINUTES)
    TvPlayerScreen(
        state = state,
        sleep = comfortState.sleep,
        screenOffMinutes = screenOffMinutes,
        onToggle = viewModel::togglePlayPause,
        onSeekBy = viewModel::seekBy,
        onChapter = viewModel::seekToChapter,
        onSpeed = viewModel::setSpeed,
        onSleep = { mode -> if (mode == null) comfort.cancelSleep() else comfort.startSleep(mode) },
        onExtendSleep = comfort::extendSleep,
        onBack = onBack,
    )
}

/**
 * The player (TV spec: "Player"): a large cover, title, author, the chapter with its progress and times, the book's time
 * left. On the stage OK pauses and resumes, left and right seek, down opens the bar with chapters, speed and sleep timer; up
 * closes it again. While the sleep timer ends, OK extends it.
 *
 * "Screen off" (TV spec 2.8): after [screenOffMinutes] without a key press while playing, the page turns black, with no text
 * and nothing that moves; the next key only brings it back, except OK while the sleep timer ends, which extends it in the dark.
 * 0 never. A pause of more than [PAUSE_WAKES_MS] brings it back too (buffering does not), and the TV's own screen saver may
 * then take over: the app never holds the screen awake (TV spec E7). Coming back to the page (after HOME) starts the wait
 * again on a lit page.
 *
 * Nothing loaded: a hint, and [onBack] to leave.
 */
@Composable
fun TvPlayerScreen(
    state: PlayerScreenState,
    sleep: SleepTimerUi?,
    screenOffMinutes: Int,
    onToggle: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onChapter: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (SleepMode?) -> Unit,
    onExtendSleep: () -> Unit,
    onBack: () -> Unit = {},
) {
    if (state.itemId == null) {
        TvMessage(stringResource(R.string.tv_player_nothing), stringResource(R.string.tv_back), onBack)
        return
    }
    var screenOff by remember { mutableStateOf(false) }
    // Counts key presses: each one starts the wait for "screen off" again.
    var presses by remember { mutableIntStateOf(0) }
    // The key that woke the screen: its repeats and its release are swallowed too, so holding it does nothing more.
    var swallowing by remember { mutableStateOf<Key?>(null) }
    val sleepEnding = sleep?.shakeToExtend == true
    // Paused for real, not just buffering: Media3 reports "not playing" during every stall, file switch and seek, and that
    // must not light up a dark room.
    var paused by remember { mutableStateOf(!state.isPlaying) }
    val playing = state.isPlaying
    LaunchedEffect(playing) {
        if (playing) paused = false
        else {
            delay(PAUSE_WAKES_MS)
            paused = true
        }
    }
    // The wait counts only while the page is on screen: after HOME the person comes back to the picture, not to black.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        screenOff = false
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(presses, paused, screenOffMinutes, screenOff, resumed) {
        if (screenOff) {
            if (paused) screenOff = false
            return@LaunchedEffect
        }
        if (!resumed || paused || screenOffMinutes <= 0) return@LaunchedEffect
        delay(screenOffMinutes * 60_000L)
        screenOff = true
    }
    // Back that wakes the screen must not leave the page as well, also when it does not come as a key event.
    BackHandler(enabled = screenOff || swallowing == Key.Back) { screenOff = false }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val key = event.key
                if (swallowing == key) {
                    if (event.type == KeyEventType.KeyUp) swallowing = null
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                presses++
                if (!screenOff) return@onPreviewKeyEvent false
                when (tvPlayerAction(key, screenOff = true, sleepEnding, 0, 0)) {
                    // In the dark: on with the listening, and the room stays dark.
                    TvPlayerAction.ExtendSleep -> onExtendSleep()
                    else -> {
                        screenOff = false
                        if (key in SYSTEM_KEYS) return@onPreviewKeyEvent false
                    }
                }
                swallowing = key
                true
            },
    ) {
        if (screenOff) ScreenOff() else Player(state, sleep, sleepEnding, onToggle, onSeekBy, onChapter, onSpeed, onSleep, onExtendSleep)
    }
}

/** Black and nothing else; it holds the focus so that the next key reaches the page. */
@Composable
private fun ScreenOff() {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().testTag(TAG_SCREEN_OFF))
}

@Composable
private fun Player(
    state: PlayerScreenState,
    sleep: SleepTimerUi?,
    sleepEnding: Boolean,
    onToggle: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onChapter: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onSleep: (SleepMode?) -> Unit,
    onExtendSleep: () -> Unit,
) {
    val stage = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val chaptersButton = remember { FocusRequester() }
    val sleepButton = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var controls by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(TvPlayerPanel.None) }
    var stageFocused by remember { mutableStateOf(false) }
    // When the last seek of the stage was (key event time); none yet: the first repeat seeks too.
    var lastSeekAt by remember { mutableStateOf<Long?>(null) }
    var pageHasFocus by remember { mutableStateOf(false) }
    val hasChapters = state.chapters.isNotEmpty()

    LaunchedEffect(Unit) {
        when {
            panel != TvPlayerPanel.None -> Unit // the panel takes it
            controls -> firstControl.requestFocusWhenPlaced()
            else -> stage.requestFocusWhenPlaced()
        }
    }
    fun hideControls() {
        controls = false
        scope.launch { stage.requestFocusWhenPlaced() }
    }
    fun closePanel() {
        val back = if (panel == TvPlayerPanel.Sleep) sleepButton else chaptersButton
        panel = TvPlayerPanel.None
        scope.launch { back.requestFocusWhenPlaced() }
    }
    // The timer's last minute: OK must extend it wherever the focus was, so the bar and any list make way for the stage.
    LaunchedEffect(sleepEnding) {
        if (!sleepEnding) return@LaunchedEffect
        controls = false
        panel = TvPlayerPanel.None
        stage.requestFocusWhenPlaced()
    }
    // Another title without chapters: their list has nothing left to show.
    LaunchedEffect(hasChapters) { if (!hasChapters && panel == TvPlayerPanel.Chapters) panel = TvPlayerPanel.None }
    // The bar's focused button went (the "Chapters" button of a title without chapters): Compose hands the focus to the stage
    // around it, where left and right would seek under an open bar. On to the bar's first button instead.
    LaunchedEffect(stageFocused, controls, panel) {
        if (stageFocused && controls && panel == TvPlayerPanel.None) firstControl.requestFocusWhenPlaced()
    }
    // The focused element went with nothing around it to take the focus (a list that closed by itself): it would be on
    // nothing. Given a few frames first, as closing a list moves the focus back to its button by itself.
    LaunchedEffect(pageHasFocus) {
        if (pageHasFocus) return@LaunchedEffect
        repeat(FOCUS_SETTLE_FRAMES) { withFrameNanos { } }
        if (pageHasFocus) return@LaunchedEffect
        if (controls && panel == TvPlayerPanel.None) firstControl.requestFocusWhenPlaced() else stage.requestFocusWhenPlaced()
    }
    BackHandler(enabled = panel != TvPlayerPanel.None) { closePanel() }
    BackHandler(enabled = controls && panel == TvPlayerPanel.None) { hideControls() }

    Box(Modifier.fillMaxSize().onFocusChanged { pageHasFocus = it.hasFocus }) {
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(stage)
                .onFocusChanged { stageFocused = it.isFocused }
                .onKeyEvent { event ->
                    // Keys the bar's buttons leave alone pass by here as well; only the stage itself acts on them.
                    if (!stageFocused || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val action = tvPlayerAction(event.key, false, sleepEnding, state.seekBackSec, state.seekForwardSec)
                        ?: return@onKeyEvent false
                    val repeat = event.nativeKeyEvent.repeatCount > 0
                    when (action) {
                        is TvPlayerAction.SeekBy -> {
                            val now = event.nativeKeyEvent.eventTime
                            val last = lastSeekAt
                            if (!repeat || last == null || now - last >= SEEK_REPEAT_MS) {
                                lastSeekAt = now
                                onSeekBy(action.sec)
                            }
                        }
                        // Holding OK or down acts once.
                        TvPlayerAction.Toggle -> if (!repeat) onToggle()
                        TvPlayerAction.ExtendSleep -> if (!repeat) onExtendSleep()
                        TvPlayerAction.ShowControls -> if (!repeat) {
                            controls = true
                            scope.launch { firstControl.requestFocusWhenPlaced() }
                        }
                        TvPlayerAction.Wake -> Unit
                    }
                    true
                }
                .focusable()
                .testTag(TAG_STAGE)
                .padding(horizontal = TvDimens.overscanHorizontal, vertical = TvDimens.overscanVertical),
        ) {
            Stage(state, sleep, sleepEnding, Modifier.weight(1f))
            if (controls) {
                TvControlBar(
                    speed = state.speed,
                    sleep = sleep,
                    hasChapters = hasChapters,
                    first = firstControl,
                    chaptersButton = chaptersButton,
                    sleepButton = sleepButton,
                    onChapters = { panel = TvPlayerPanel.Chapters },
                    onSpeed = onSpeed,
                    onSleep = { panel = TvPlayerPanel.Sleep },
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) hideControls()
                            true
                        },
                )
            } else {
                Text(
                    stringResource(R.string.tv_player_more_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        when (panel) {
            TvPlayerPanel.Chapters -> ChapterPanel(
                state.chapters,
                onChapter = { index ->
                    onChapter(index)
                    closePanel()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            TvPlayerPanel.Sleep -> TvSleepPanel(
                sleep?.mode,
                onSleep = { mode ->
                    onSleep(mode)
                    closePanel()
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            TvPlayerPanel.None -> Unit
        }
    }
}

/** What the stage shows: the cover on the left, on the right what plays and where it stands. */
@Composable
private fun Stage(state: PlayerScreenState, sleep: SleepTimerUi?, sleepEnding: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        // As on the phone: a server cover only once this title's version is known, so it is not requested twice.
        val art = state.itemId?.let { id ->
            if (state.coverPath == null && state.coverVersion == null) null
            else coverModel(LocalServerBaseUrl.current, id, state.coverPath, state.coverVersion ?: 0)
        }
        CoverArt(
            art, state.title,
            Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clearAndSetSemantics {}, shape = RoundedCornerShape(12.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            state.author?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            state.chapterTitle?.takeIf { state.splitByChapters }?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
            }
            Progress(state.chapterProgress, Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(state.chapterElapsedText, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text("−" + formatClock(state.chapterRemainingSec), style = MaterialTheme.typography.bodyLarge)
            }
            if (state.splitByChapters) {
                Text(
                    stringResource(R.string.tv_player_book_left, formatClock(state.bookRemainingSec)),
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
                )
            }
            Status(state, sleep, sleepEnding)
        }
    }
}

/** Playing or paused (what OK does next), loading, an error, the sleep timer and, at its end, how to extend it. */
@Composable
private fun Status(state: PlayerScreenState, sleep: SleepTimerUi?, sleepEnding: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val label = stringResource(if (state.isPlaying) R.string.tv_player_playing else R.string.tv_player_paused)
        Icon(
            if (state.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null,
            modifier = Modifier.size(28.dp).semantics { contentDescription = label },
        )
        when {
            state.error != null -> Text(stringResource(state.error), style = MaterialTheme.typography.bodyLarge, color = scheme.error)
            state.isLoading -> Text(stringResource(R.string.player_loading), style = MaterialTheme.typography.bodyLarge)
            !state.isPlaying -> Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (sleep != null) {
        Text(sleepLabel(sleep), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
    if (sleepEnding) {
        Text(
            stringResource(R.string.tv_player_extend_hint), style = MaterialTheme.typography.titleMedium, color = scheme.primary,
            modifier = Modifier.testTag(TAG_EXTEND_HINT),
        )
    }
}

/** The chapter's progress as a plain bar: no animation, so a still picture stays still. */
@Composable
private fun Progress(fraction: Float, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Said as a progress bar; the times beside it say the rest.
    Box(
        modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.surfaceVariant)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) }
            .testTag(TAG_PROGRESS),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(scheme.primary))
    }
}
