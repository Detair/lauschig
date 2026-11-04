package io.wolftown.lauschig.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.ui.theme.Spacing
import kotlin.math.max
import kotlin.math.min

/**
 * Plain bars on a baseline (Canvas, no chart library): every bar in the neutral [barColor], the one at [current] in
 * [currentColor] – the one accent. Heights are relative to the largest value; a value above zero always shows a sliver.
 * [labels] (null entries stay empty) sit under their bars. For accessibility the chart is one node whose
 * [description] lists the values; the drawing and the labels are hidden behind it.
 */
@Composable
fun BarChart(
    values: List<Double>,
    current: Int?,
    description: String,
    modifier: Modifier = Modifier,
    labels: List<String?>? = null,
    height: Dp = 140.dp,
    barColor: Color = MaterialTheme.colorScheme.outline,
    currentColor: Color = MaterialTheme.colorScheme.primary,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Column(modifier.clearAndSetSemantics { contentDescription = description }) {
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
            val floor = size.height - line
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = max(sliver, (v / top).toFloat() * floor)
                val slotIndex = if (rtl) n - 1 - i else i
                drawRoundRect(
                    color = if (i == current) currentColor else barColor,
                    topLeft = Offset(slot * slotIndex + (slot - barWidth) / 2, floor - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            drawLine(baselineColor, Offset(0f, size.height - line / 2), Offset(size.width, size.height - line / 2), strokeWidth = line)
        }
        if (labels != null) {
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                labels.forEachIndexed { i, label ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (label != null) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
