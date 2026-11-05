package io.wolftown.lauschig.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import io.wolftown.lauschig.R
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.theme.contrastRatio
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The colour of the plain bars: the scheme's `outline` where it keeps 3:1 against [surface] (graphics contrast, WCAG
 * 1.4.11), else `onSurfaceVariant`.
 */
fun statsBarColor(scheme: ColorScheme, surface: Color = scheme.surface): Color =
    if (contrastRatio(scheme.outline, surface) >= 3.0) scheme.outline else scheme.onSurfaceVariant

/** What a bar's tooltip says: [title] (its day, month or year) and [value] (the time heard). */
@Immutable
data class BarTip(val title: String, val value: String)

/**
 * Plain bars on a baseline (Canvas, no chart library): every bar in the neutral [barColor], the one at [current] in
 * [currentColor] – the one accent – and marked by more than colour: a dot below it and a bold label. Heights are
 * relative to the largest value; a value above zero always shows a sliver. [labels] (null entries stay empty) sit under
 * their bars.
 *
 * Without [tips] the chart is one quiet node whose [description] lists the values (the drawing and the labels are
 * hidden behind it). With [tips] (one per bar) a tap anywhere in a bar's slot marks its column and shows a small
 * tooltip above it with its date and time – in a band kept free above the bars, so it never covers one; dragging
 * across the chart scrubs from bar to bar; tapping the selected bar again or the band clears it. For a screen reader
 * the [description] comes first, then every bar is its own tab-like node that says its tip (exploring by touch reads
 * the bar under the finger) and can be selected like a tap; the summary sits on the labels' strip, since Compose hides
 * nodes that others cover from screen readers. [height] is the bars' height; the band comes on top of it.
 */
@Composable
fun BarChart(
    values: List<Double>,
    current: Int?,
    /** What a screen reader says for the chart; null hides it (a card that says it in words already). */
    description: String?,
    modifier: Modifier = Modifier,
    labels: List<String?>? = null,
    height: Dp = 140.dp,
    barColor: Color = statsBarColor(MaterialTheme.colorScheme),
    currentColor: Color = MaterialTheme.colorScheme.primary,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
    /** The labels' colour; the current bar's label is in [currentLabelColor] (and bold). */
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    currentLabelColor: Color = MaterialTheme.colorScheme.primary,
    /** One per bar: makes the bars tappable, with a tooltip (see above); null keeps the chart a picture. */
    tips: List<BarTip>? = null,
) {
    val barTips = tips?.takeIf { it.size == values.size && values.isNotEmpty() }
    val interactive = barTips != null
    var selection by remember { mutableStateOf<Int?>(null) }
    val selected = selection?.takeIf { it in values.indices }
    val semantics = if (interactive) Modifier.semantics { isTraversalGroup = true }
    else Modifier.clearAndSetSemantics { if (description != null) contentDescription = description }
    // Room for the tooltip above the bars: its two lines (they grow with the text size – each converted on its own, as
    // the text is: large sizes scale less on Android 14+), its padding and a gap.
    val band = if (interactive) with(LocalDensity.current) { TOOLTIP_LINE_1.toDp() + TOOLTIP_LINE_2.toDp() + TOOLTIP_PADDING * 2 + TOOLTIP_GAP } else 0.dp
    // The accent, faint: visible over an empty slot and on pure black alike.
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val count = values.size
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bandPx = with(LocalDensity.current) { band.toPx() }
    val shownNow by rememberUpdatedState(selected)
    // Touch: a tap selects the bar of its slot wherever in the chart (thin month bars are no lottery), the selected one
    // again or the tooltip's band clears; a horizontal drag scrubs, the bar under the finger follows. Screen readers use
    // the bars' own nodes below instead.
    val touch = if (interactive) {
        Modifier
            .testTag("chartArea")
            .pointerInput(count, rtl, bandPx) {
                detectTapGestures { at ->
                    val i = slotAt(at.x, count, size.width, rtl)
                    selection = when (shownNow) {
                        null -> i
                        i -> null
                        else -> if (at.y < bandPx) null else i
                    }
                }
            }
            .pointerInput(count, rtl) {
                detectHorizontalDragGestures(onDragStart = { at -> selection = slotAt(at.x, count, size.width, rtl) }) { change, _ ->
                    change.consume()
                    selection = slotAt(change.position.x, count, size.width, rtl)
                }
            }
    } else {
        Modifier
    }
    Column(modifier.then(semantics)) {
        Box(Modifier.fillMaxWidth().height(height + band).then(touch)) {
            Canvas(Modifier.matchParentSize()) {
                val n = values.size
                if (n == 0) return@Canvas
                val rtl = layoutDirection == LayoutDirection.Rtl
                val slot = size.width / n
                val barWidth = min(slot * 0.62f, 28.dp.toPx())
                val radius = min(barWidth / 2, 4.dp.toPx())
                val line = 1.dp.toPx()
                val dot = min(2.dp.toPx(), slot / 4)
                val floor = barFloor(size.height)
                val ceiling = band.toPx()
                // The selected bar's column, from the band down to the baseline (a bar of nothing has one, too).
                selected?.let { i ->
                    val width = min(slot * 0.9f, barWidth + 12.dp.toPx())
                    drawRoundRect(
                        highlight, Offset(barCenterX(i, n, size.width, rtl) - width / 2, ceiling), Size(width, floor - ceiling),
                        CornerRadius(radius + 2.dp.toPx(), radius + 2.dp.toPx()),
                    )
                }
                values.forEachIndexed { i, v ->
                    if (v <= 0) return@forEachIndexed
                    val top = barTop(values, i, floor, ceiling)
                    val x = barCenterX(i, n, size.width, rtl)
                    drawRoundRect(
                        color = if (i == current) currentColor else barColor,
                        topLeft = Offset(x - barWidth / 2, top),
                        size = Size(barWidth, floor - top),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
                drawLine(baselineColor, Offset(0f, floor + line / 2), Offset(size.width, floor + line / 2), strokeWidth = line)
                if (current != null && current in values.indices) {
                    drawCircle(currentColor, radius = dot, center = Offset(barCenterX(current, n, size.width, rtl), floor + line + MARKER_SPACE.toPx() / 2))
                }
            }
            if (barTips != null) {
                val spoken = barTips.map { stringResource(R.string.stats_bar_tip, it.title, it.value) }
                // For screen readers: one node per bar (its slot over the whole height, so exploring by touch finds it), tabs
                // of one group, each selected like a tap. Touches pass through them to the chart's gestures above. A Row
                // mirrors itself in RTL like the bars.
                Row(Modifier.matchParentSize().selectableGroup()) {
                    values.indices.forEach { i ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics {
                                    contentDescription = spoken[i]
                                    role = Role.Tab
                                    this.selected = selected == i
                                    onClick { selection = if (shownNow == i) null else i; true }
                                }
                                .testTag("chartBar:$i"),
                        )
                    }
                }
                BarTooltip(values.size, selected, barTips)
            }
        }
        // Interactive, the labels' strip carries the spoken summary, read first: the drawing lies under the bars' nodes,
        // which hide it from screen readers (Compose leaves out what is covered).
        val labelTexts = labels ?: if (interactive) List(values.size) { null } else null
        if (labelTexts != null) {
            val summary = if (interactive) {
                Modifier.testTag("chartSummary").clearAndSetSemantics {
                    if (description != null) contentDescription = description
                    traversalIndex = -1f
                }
            } else {
                Modifier
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs).then(summary)) {
                labelTexts.forEachIndexed { i, label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (label != null) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (i == current) currentLabelColor else labelColor,
                                fontWeight = if (i == current) FontWeight.Bold else null,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                                // A label may be wider than its bar's slot (days of a month): centred on it, not cut.
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The tooltip of the [selected] bar: time (bold) over date, in the inverse surface, at the top of the chart (the band
 * above the bars) and centred over its bar as far as the chart allows. It fades and grows in, glides from bar to bar and
 * fades out; touches pass through it to the bars. Hidden from screen readers: the bar itself says the same.
 */
@Composable
private fun BoxScope.BarTooltip(count: Int, selected: Int?, tips: List<BarTip>) {
    // The last selected bar, so the tooltip can fade out where it was (a plain holder: no state written while composing).
    val shown = remember { LastShown() }
    if (selected != null) shown.index = selected
    val alpha by animateFloatAsState(if (selected != null) 1f else 0f, tween(TOOLTIP_MS), label = "tooltipAlpha")
    val index = shown.index ?: return
    if (selected == null && alpha == 0f) return
    // Created with the tooltip, so it starts at its first bar and only glides when the selection moves.
    val position by animateFloatAsState(index.toFloat(), tween(TOOLTIP_MS), label = "tooltipPosition")
    val tip = tips[index]
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val width = constraints.maxWidth
                val rtl = layoutDirection == LayoutDirection.Rtl
                val lower = position.toInt().coerceIn(0, count - 1)
                val upper = (lower + 1).coerceAtMost(count - 1)
                val x = lerp(barCenterX(lower, count, width.toFloat(), rtl), barCenterX(upper, count, width.toFloat(), rtl), position - lower)
                layout(width, constraints.maxHeight) {
                    p.place((x - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0)), 0)
                }
            }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = 0.92f + 0.08f * alpha
                scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .background(scheme.inverseSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.s, vertical = TOOLTIP_PADDING)
            .semantics(mergeDescendants = true) { hideFromAccessibility() }
            .testTag("barTooltip"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            tip.value,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", lineHeight = TOOLTIP_LINE_1),
            fontWeight = FontWeight.Bold,
            color = scheme.inverseOnSurface,
            maxLines = 1,
        )
        Text(tip.title, style = MaterialTheme.typography.labelSmall.copy(lineHeight = TOOLTIP_LINE_2), color = scheme.inverseOnSurface, maxLines = 1)
    }
}

/** The tooltip's two lines (time, date), its vertical padding and the gap to the bars below it. */
private val TOOLTIP_LINE_1 = 20.sp
private val TOOLTIP_LINE_2 = 16.sp
private val TOOLTIP_PADDING = 4.dp
private val TOOLTIP_GAP = 6.dp

private class LastShown {
    var index: Int? = null
}

/** How long the tooltip takes to fade in or out and to glide to another bar. */
private const val TOOLTIP_MS = 180

/** Where the bars stand in a chart [height] px high: above the baseline and the room for the current bar's dot. */
private fun Density.barFloor(height: Float): Float = height - 1.dp.toPx() - MARKER_SPACE.toPx()

/** The bar whose slot holds [x] (px) among [n] in a chart [width] wide, mirrored right to left. */
internal fun slotAt(x: Float, n: Int, width: Int, rtl: Boolean): Int {
    val i = (x / width * n).toInt().coerceIn(0, n - 1)
    return if (rtl) n - 1 - i else i
}

/** Bar [i]'s centre (px) among [n] in a chart [width] wide, mirrored right to left. */
internal fun barCenterX(i: Int, n: Int, width: Float, rtl: Boolean): Float {
    val slot = width / n
    return slot * (if (rtl) n - 1 - i else i) + slot / 2
}

/**
 * Bar [i]'s top (px) on [floor], the largest reaching [ceiling]: relative to the largest value, a sliver at least above
 * zero, the floor for nothing.
 */
private fun Density.barTop(values: List<Double>, i: Int, floor: Float, ceiling: Float): Float {
    val v = values[i]
    if (v <= 0) return floor
    val top = values.max().takeIf { it > 0 } ?: 1.0
    return floor - max(2.dp.toPx(), (v / top).toFloat() * (floor - ceiling))
}

/** Below the baseline: where the current bar's dot sits. */
private val MARKER_SPACE = 8.dp
