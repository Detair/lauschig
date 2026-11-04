package io.wolftown.lauschig.ui.review

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.common.LocalBackdropReach
import io.wolftown.lauschig.ui.findActivity
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.theme.isDark

/** How long a slide stays before the story moves on by itself. */
const val STORY_SLIDE_MS = 7_000L

/** A swipe down this far (or flung down) closes the story. */
private val CLOSE_DRAG = 120.dp

/** A fling down at least this fast closes the story, however short the drag. */
private const val CLOSE_FLING_DP_PER_SEC = 1_200f

/** The widest the story gets (tablets, landscape): centred, never stretched. */
val STORY_MAX_WIDTH = 560.dp

/** The room a slide has (inside the pager, before it scrolls): a wide one lays its parts side by side. */
val LocalStoryViewport = staticCompositionLocalOf { DpSize(360.dp, 640.dp) }

/** A slide's colours: its background and the colour of everything on it. */
@Immutable
data class SlideColors(val background: Color, val content: Color)

/**
 * How the story moves. [autoAdvance]: on by itself every [STORY_SLIDE_MS] – off for TalkBack (it would fight the
 * reading) and with the system's animations removed (nothing moves by itself then); the story is navigated by tap, keys
 * and buttons instead. Other assistive services (Switch Access, Voice Access, a password manager) keep auto-advance,
 * with the longer slide time the user asked for ("time to take action"), the slide's click and actions, and the keys.
 * [animations]: the entrance of a slide (a number counting up) and the cross-fades. [keys]: the story takes the
 * keyboard's focus for its arrow and space keys – not with TalkBack, which has the buttons and the slide's actions (an
 * input-focused container would only be one more silent stop).
 */
@Immutable
data class StoryMotion(val autoAdvance: Boolean = true, val animations: Boolean = true, val keys: Boolean = true)

/**
 * [StoryMotion] as the device has it: TalkBack (touch exploration) followed live; the animator scale ("remove
 * animations") read when the screen is composed and whenever it resumes (the setting is changed outside the app).
 */
@Composable
fun rememberStoryMotion(): StoryMotion {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    var animations by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    // "Remove animations" may have changed while the app was away.
    LifecycleResumeEffect(Unit) {
        animations = ValueAnimator.areAnimatorsEnabled()
        onPauseOrDispose {}
    }
    return StoryMotion(autoAdvance = animations && !touchExploration, animations = animations, keys = !touchExploration)
}

/** Where a segment of the progress bar stands (for tests; segments say nothing to TalkBack). */
enum class SegmentState { DONE, CURRENT, TODO }

val SegmentStateKey = SemanticsPropertyKey<SegmentState>("StorySegment")
private var SemanticsPropertyReceiver.segmentState by SegmentStateKey

/**
 * The story's position: the slide shown, how far its time has run ([progress], 0..1) and which slides were shown
 * already (each slide's entrance plays once). [onPlace] hears every change of the slide or of the shown ones (a view
 * model keeps them across a process death); the running time is not kept.
 */
@Stable
class StoryState(
    count: Int,
    index: Int = 0,
    progress: Float = 0f,
    shown: Set<Int> = emptySet(),
    private val onPlace: (index: Int, shown: Set<Int>) -> Unit = { _, _ -> },
) {
    /** How many slides; changes only by [relocate]. */
    var count by mutableIntStateOf(count)
        private set
    var index by mutableIntStateOf(index.coerceIn(0, (count - 1).coerceAtLeast(0)))
        private set
    var progress by mutableFloatStateOf(progress)
        internal set
    private val shown = shown.filterTo(HashSet()) { it in 0 until count }

    val isLast: Boolean get() = index >= count - 1

    /** The slides whose entrance has played. */
    val shownSlides: Set<Int> get() = shown.toSet()

    /** The next slide; false (nothing changes) on the last one. */
    fun next(): Boolean {
        if (isLast) return false
        index++
        progress = 0f
        onPlace(index, shownSlides)
        return true
    }

    /** The slide before; on the first one, its time starts again. */
    fun previous() {
        if (index > 0) {
            index--
            onPlace(index, shownSlides)
        }
        progress = 0f
    }

    /** True the first time slide [i] is shown: its entrance plays then, never again. */
    fun firstShow(i: Int): Boolean = shown.add(i).also { if (it) onPlace(index, shownSlides) }

    /**
     * The story was rebuilt: now [count] slides, standing on [index] with [shown] played (the caller maps the old place
     * onto the new slides). The running time of the slide stays.
     */
    fun relocate(count: Int, index: Int, shown: Set<Int>) {
        this.count = count
        this.index = index.coerceIn(0, (count - 1).coerceAtLeast(0))
        this.shown.clear()
        shown.filterTo(this.shown) { it in 0 until count }
        onPlace(this.index, shownSlides)
    }
}

/**
 * A story: full-screen slides one after the other, with segmented progress bars and a close button on top.
 *
 * - Moves on by itself after [slideMillis] (or the longer time the user asked apps to leave them, Android's "time to
 *   take action") while [StoryMotion.autoAdvance]; it stays on the last slide.
 * - A tap on the right two thirds of the window is the next slide, on the left third the one before (mirrored
 *   right-to-left); a press held down pauses; a swipe down or the X closes ([onClose]).
 * - It never moves while the screen is not in front (the app in the background, a dialog), held down or pulled down.
 * - Without auto-advance (TalkBack, animations removed) there are buttons for the previous and next slide. For TalkBack
 *   each slide is one item that reads [description] (its position first) and is announced when the slide changes.
 * - The background is each slide's own ([colors]), cross-faded, and reaches under the status bar and the navigation bar
 *   where the frame allows it; the content is centred with at most [STORY_MAX_WIDTH] and scrolls when it does not fit.
 *
 * [content] draws a slide; `entrance` is true when its entrance should play (the first time it is shown, animations on).
 */
@Composable
fun StoryPager(
    state: StoryState,
    onClose: () -> Unit,
    title: String,
    colors: @Composable (index: Int) -> SlideColors,
    description: @Composable (index: Int) -> String,
    modifier: Modifier = Modifier,
    motion: StoryMotion = StoryMotion(),
    slideMillis: Long = STORY_SLIDE_MS,
    content: @Composable (index: Int, entrance: Boolean) -> Unit,
) {
    val index = state.index
    val slide = colors(index)
    val fade = if (motion.animations) 300 else 0
    val background by animateColorAsState(slide.background, tween(fade), label = "storyBackground")
    val foreground by animateColorAsState(slide.content, tween(fade), label = "storyContent")
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var held by remember { mutableStateOf(false) }
    // Held past the long-press time: shown as paused (a tap's short press is not).
    var holding by remember { mutableStateOf(false) }
    // Space on a keyboard: paused until pressed again.
    var keyPaused by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    // The pull is read where it is drawn; composition only needs to know whether there is one.
    val pulled by remember { derivedStateOf { drag != 0f } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus, motion.keys) { if (motion.keys) focus.requestFocus() }
    val density = LocalDensity.current
    val closeAt = with(density) { CLOSE_DRAG.toPx() }
    val closeFling = with(density) { CLOSE_FLING_DP_PER_SEC.dp.toPx() }
    val a11y = LocalAccessibilityManager.current
    val duration = remember(a11y, slideMillis) { a11y?.calculateRecommendedTimeoutMillis(slideMillis, containsIcons = true, containsText = true) ?: slideMillis }
    val running = motion.autoAdvance && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !held && !keyPaused && !pulled

    // Frame time, not an animation: a removed animator scale would finish an animation at once.
    LaunchedEffect(state, index, running, duration) {
        if (!running) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (state.progress < 1f) {
            val now = withFrameMillis { it }
            state.progress = (state.progress + (now - last).toFloat() / duration).coerceAtMost(1f)
            last = now
        }
        state.next()
    }

    val pull = remember(closeAt, closeFling) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pulled down, then back up: the pull gives way first.
                if (drag > 0f && available.y < 0f) {
                    val used = maxOf(available.y, -drag)
                    drag += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // What the slide could not scroll (it is at its top) pulls the story down.
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    drag += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (drag <= 0f) return Velocity.Zero
                if (drag >= closeAt || available.y >= closeFling) {
                    onClose()
                } else {
                    animate(drag, 0f, animationSpec = tween(if (motion.animations) 200 else 0)) { v, _ -> drag = v }
                    drag = 0f
                }
                return available
            }
        }
    }

    StatusBarIconsFor(slide.content)

    val position = stringResource(R.string.review_slide_position, index + 1, state.count)
    val spoken = description(index)
    val nextLabel = stringResource(R.string.review_next)
    val previousLabel = stringResource(R.string.review_previous)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reach = LocalBackdropReach.current
    CompositionLocalProvider(LocalContentColor provides foreground) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .drawBehind {
                    val top = reach.top.toPx()
                    val left = reach.left.toPx()
                    val right = reach.right.toPx()
                    val bottom = reach.bottom.toPx()
                    drawRect(background, topLeft = Offset(-left, -top), size = Size(size.width + left + right, size.height + top + bottom))
                }
                .semantics { paneTitle = title }
                // Keys (a tablet's keyboard, a D-pad): right/left = next/previous (mirrored right-to-left) and end a pause,
                // space pauses and resumes; a held key does it once. Keys a focused button uses itself (space, enter)
                // stay its own.
                .then(if (motion.keys) Modifier.focusRequester(focus).focusable() else Modifier)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val ours = e.key == Key.DirectionRight || e.key == Key.DirectionLeft || e.key == Key.Spacebar
                    if (e.nativeKeyEvent.repeatCount > 0) return@onKeyEvent ours
                    when (e.key) {
                        Key.DirectionRight -> { keyPaused = false; if (rtl) state.previous() else state.next(); true }
                        Key.DirectionLeft -> { keyPaused = false; if (rtl) state.next() else state.previous(); true }
                        Key.Spacebar -> { keyPaused = !keyPaused; true }
                        else -> false
                    }
                }
                // The whole window is the tap area (on a tablet the sides beside the centred story too).
                .pointerInput(state, rtl) {
                    detectTapGestures(
                        onPress = {
                            held = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                held = false
                                holding = false
                            }
                        },
                        // A long press only pauses; its release moves nothing.
                        onLongPress = { holding = true },
                        onTap = { at ->
                            val third = size.width / 3f
                            val back = if (rtl) at.x > size.width - third else at.x < third
                            if (back) state.previous() else state.next()
                        },
                    )
                }
                .testTag("story"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = STORY_MAX_WIDTH).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Segments(state, foreground, Modifier.weight(1f), running = motion.autoAdvance)
                    // Paused (held, or space): a small mark beside the segments; its room is kept, so nothing moves.
                    Box(Modifier.padding(start = Spacing.s).size(20.dp), contentAlignment = Alignment.Center) {
                        if (motion.autoAdvance && (holding || keyPaused)) {
                            Icon(Icons.Rounded.Pause, stringResource(R.string.review_paused), Modifier.size(20.dp).testTag("storyPaused"))
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = foreground),
                        modifier = Modifier.padding(start = Spacing.xs).testTag("storyClose"),
                    ) { Icon(Icons.Rounded.Close, stringResource(R.string.review_close)) }
                }
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(pull),
                ) {
                    val viewport = DpSize(maxWidth, maxHeight)
                    val scroll = rememberScrollState()
                    LaunchedEffect(index) { scroll.scrollTo(0) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .heightIn(min = maxHeight)
                            .graphicsLayer {
                                translationY = drag / 2f
                                alpha = 1f - (drag / (closeAt * 3f)).coerceIn(0f, 0.5f)
                            }
                            .testTag("storySlide")
                            // One item for TalkBack, said again when the slide changes.
                            .clearAndSetSemantics {
                                contentDescription = "$position. $spoken"
                                liveRegion = LiveRegionMode.Polite
                                // A double tap goes on (said as such), like a tap on the right.
                                if (!state.isLast) onClick(label = nextLabel) { state.next() }
                                // Only where they lead somewhere: no "next" on the last slide, no "previous" on the first.
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction(nextLabel) { state.next() }.takeIf { !state.isLast },
                                    CustomAccessibilityAction(previousLabel) { state.previous(); true }.takeIf { index > 0 },
                                )
                            }
                            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CompositionLocalProvider(LocalStoryViewport provides viewport) {
                            Crossfade(index, animationSpec = tween(if (motion.animations) 250 else 0), label = "storySlide") { shown ->
                                val entrance = remember(shown) { state.firstShow(shown) } && motion.animations
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content(shown, entrance) }
                            }
                        }
                    }
                }
                if (!motion.autoAdvance) {
                    ManualControls(state, foreground, previousLabel, nextLabel)
                }
            }
        }
    }
}

/**
 * The status and navigation bar icons in the slide's own text colour, dark or light (the slides reach under the bars,
 * and a theme's containers can be light in a dark theme). When the story goes they follow the app's theme again, as
 * everywhere else (see MainActivity).
 */
@Composable
private fun StatusBarIconsFor(content: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val controller = remember(view) { view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) } } ?: return
    val themeDark by rememberUpdatedState(MaterialTheme.colorScheme.isDark)
    DisposableEffect(controller) {
        onDispose {
            controller.isAppearanceLightStatusBars = !themeDark
            controller.isAppearanceLightNavigationBars = !themeDark
        }
    }
    // "Light bars" = dark icons: where the slide's text is dark.
    val light = content.luminance() < 0.5f
    SideEffect {
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}

/**
 * One segment per slide: the past ones full, the current one as far as its time has run ([running]; full when the
 * story does not move by itself – it only marks the place), the rest empty.
 */
@Composable
private fun Segments(state: StoryState, color: Color, modifier: Modifier = Modifier, running: Boolean = true) {
    Row(modifier.height(Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        repeat(state.count) { i ->
            val segment = when {
                i < state.index -> SegmentState.DONE
                i == state.index -> SegmentState.CURRENT
                else -> SegmentState.TODO
            }
            Canvas(Modifier.weight(1f).height(3.dp).semantics { segmentState = segment }.testTag("storySegment")) {
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(color.copy(alpha = 0.3f), cornerRadius = radius)
                // Read in the draw phase only: the running time redraws, it never recomposes.
                val fill = when (segment) {
                    SegmentState.DONE -> 1f
                    SegmentState.CURRENT -> if (running) state.progress else 1f
                    SegmentState.TODO -> 0f
                }
                if (fill > 0f) {
                    val w = size.width * fill
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = radius)
                }
            }
        }
    }
}

/** Previous / position / next, when the story does not move by itself. */
@Composable
private fun ManualControls(state: StoryState, color: Color, previousLabel: String, nextLabel: String) {
    val buttons = IconButtonDefaults.iconButtonColors(contentColor = color, disabledContentColor = color.copy(alpha = 0.38f))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = state::previous, enabled = state.index > 0, colors = buttons, modifier = Modifier.testTag("storyPrevious")) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, previousLabel)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // The slide says its position itself.
            Text("${state.index + 1} / ${state.count}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
        }
        IconButton(onClick = { state.next() }, enabled = !state.isLast, colors = buttons, modifier = Modifier.testTag("storyNext")) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, nextLabel)
        }
    }
}
