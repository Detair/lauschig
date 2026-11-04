package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A flat progress line exactly [thickness] high (the mini player's top edge, a tile's bottom edge): [color] up to
 * [progress] (0..1) over [trackColor], with the usual progress-bar semantics.
 */
@Composable
fun ProgressLine(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    thickness: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    val value = progress().coerceIn(0f, 1f)
    Spacer(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .progressSemantics(value)
            .drawBehind {
                drawRect(trackColor)
                val end = size.width * value
                val start = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - end else 0f
                drawRect(color, topLeft = Offset(start, 0f), size = Size(end, size.height))
            },
    )
}
