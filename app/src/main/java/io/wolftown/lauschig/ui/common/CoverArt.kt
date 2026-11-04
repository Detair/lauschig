package io.wolftown.lauschig.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.wolftown.lauschig.ui.theme.initialsOf
import io.wolftown.lauschig.ui.theme.isDark
import io.wolftown.lauschig.ui.theme.placeholderToneFor

/** Corner radii of covers: tiles, cards and pages 12 dp, the full player 20 dp, the mini player 8 dp. */
object CoverShapes {
    val Tile = RoundedCornerShape(12.dp)
    val Player = RoundedCornerShape(20.dp)
    val Mini = RoundedCornerShape(8.dp)
}

/**
 * A cover: [model] (see [coverModel]) over the title's placeholder, which stays when there is no model or loading
 * fails. Dark themes get a hairline outline so a dark cover does not melt into the background.
 */
@Composable
fun CoverArt(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = CoverShapes.Tile,
    initialsStyle: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Box(modifier.clip(shape).coverOutline(shape)) {
        Initials(title, initialsStyle)
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The hairline of [CoverArt] (drawn over the content), in dark themes only. */
@Composable
fun Modifier.coverOutline(shape: Shape): Modifier {
    val scheme = MaterialTheme.colorScheme
    return if (scheme.isDark) border(Dp.Hairline, scheme.outlineVariant.copy(alpha = 0.4f), shape) else this
}

/**
 * The stand-in for a missing cover: a gentle two-tone gradient picked from the title (always the same for the same
 * title, see [placeholderToneFor]) with its initials. Fills its parent.
 */
@Composable
internal fun Initials(title: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    val scheme = MaterialTheme.colorScheme
    val tone = remember(title, scheme) { placeholderToneFor(title, scheme) }
    val initials = remember(title) { initialsOf(title) }
    val brush = remember(tone) { Brush.linearGradient(listOf(tone.start, tone.end), start = Offset.Zero, end = Offset.Infinite) }
    Box(Modifier.fillMaxSize().background(brush), contentAlignment = Alignment.Center) {
        Text(initials, style = style, color = tone.content)
    }
}
