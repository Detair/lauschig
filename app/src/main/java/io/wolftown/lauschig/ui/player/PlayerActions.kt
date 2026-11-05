package io.wolftown.lauschig.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gap between the player's tiles, horizontally and to the contents banner under them. */
val PlayerActionSpacing = 8.dp

/**
 * One of the player's equal tiles (Tempo, Sleep, Klang): icon, name and value, sharing its row equally with its
 * neighbours. [active] highlights a feature that is on; [warm] makes that highlight the warm primary container (the
 * running sleep timer). Name and value stay on one line each and shrink with large fonts, so the row keeps its height;
 * the full text belongs in the caller's semantics.
 */
@Composable
fun RowScope.PlayerTile(
    icon: ImageVector,
    name: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    warm: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        active && warm -> colors.primaryContainer to colors.onPrimaryContainer
        active -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceContainerHigh to colors.onSurface
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        modifier = modifier.weight(1f).heightIn(min = 72.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            val nameStyle = MaterialTheme.typography.labelMedium
            Text(
                name,
                style = nameStyle,
                color = if (active) content else colors.onSurfaceVariant,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = nameStyle.fontSize, stepSize = 0.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
            val valueStyle = MaterialTheme.typography.titleSmall.merge(TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold))
            Text(
                value,
                style = valueStyle,
                // One line that shrinks with large text ("Kapitelende" at 130 % on a 360 dp phone); only below the
                // smallest step it is ellipsized.
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = valueStyle.fontSize, stepSize = 0.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The seek icon: the round "replay" arrow (mirrored for forward) with the seconds inside, for every step of the settings
 * (Material only ships 5, 10 and 30 s). The number scales with the icon, not with the font size, so it always fits the
 * circle; it is hidden from accessibility (the button says "Back 15 seconds").
 */
@Composable
fun SeekIcon(seconds: Int, forward: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(size).scale(scaleX = if (forward) -1f else 1f, scaleY = 1f))
        val digits = with(LocalDensity.current) { (size * if (seconds >= 10) 0.26f else 0.30f).toSp() }
        Text(
            seconds.toString(),
            style = TextStyle(fontSize = digits, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", lineHeight = digits),
            // The arrow's circle sits a little below the middle of the icon (centre at 13 of 24).
            modifier = Modifier.padding(top = size / 12).semantics { hideFromAccessibility() }
                .testTag(if (forward) "seekForwardNumber" else "seekBackNumber"),
        )
    }
}
