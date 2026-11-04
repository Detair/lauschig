package io.wolftown.lauschig.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.ui.theme.Spacing
import io.wolftown.lauschig.ui.theme.contrastRatio
import kotlin.math.max
import kotlin.math.min

/**
 * The colour of the plain bars: the scheme's `outline` where it keeps 3:1 against [surface] (graphics contrast, WCAG
 * 1.4.11), else `onSurfaceVariant`.
 */
fun statsBarColor(scheme: ColorScheme, surface: Color = scheme.surface): Color =
    if (contrastRatio(scheme.outline, surface) >= 3.0) scheme.outline else scheme.onSurfaceVariant

/**
 * Plain bars on a baseline (Canvas, no chart library): every bar in the neutral [barColor], the one at [current] in
 * [currentColor] – the one accent – and marked by more than colour: a dot below it and a bold label. Heights are
 * relative to the largest value; a value above zero always shows a sliver. [labels] (null entries stay empty) sit under
 * their bars. For accessibility the chart is one node whose [description] lists the values; the drawing and the labels
 * are hidden behind it.
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
) {
    Column(modifier.clearAndSetSemantics { if (description != null) contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            if (n == 0) return@Canvas
            val rtl = layoutDirection == LayoutDirection.Rtl
            val top = values.max().takeIf { it > 0 } ?: 1.0
            val slot = size.width / n
            val barWidth = min(slot * 0.62f, 28.dp.toPx())
            val radius = min(barWidth / 2, 4.dp.toPx())
            val sliver = 2.dp.toPx()
            val line = 1.dp.toPx()
            val dot = min(2.dp.toPx(), slot / 4)
            // Room below the baseline for the dot that marks the current bar.
            val floor = size.height - line - MARKER_SPACE.toPx()
            fun centerX(i: Int) = slot * (if (rtl) n - 1 - i else i) + slot / 2
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = max(sliver, (v / top).toFloat() * floor)
                drawRoundRect(
                    color = if (i == current) currentColor else barColor,
                    topLeft = Offset(centerX(i) - barWidth / 2, floor - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            drawLine(baselineColor, Offset(0f, floor + line / 2), Offset(size.width, floor + line / 2), strokeWidth = line)
            if (current != null && current in values.indices) {
                drawCircle(currentColor, radius = dot, center = Offset(centerX(current), floor + line + MARKER_SPACE.toPx() / 2))
            }
        }
        if (labels != null) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                labels.forEachIndexed { i, label ->
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

/** Below the baseline: where the current bar's dot sits. */
private val MARKER_SPACE = 8.dp
