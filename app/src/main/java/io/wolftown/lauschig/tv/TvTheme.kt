package io.wolftown.lauschig.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.ui.LauschigDarkColors
import androidx.compose.material3.MaterialTheme as Material3Theme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

/** The TV palette: always the app's night blue, whatever the phone's appearance settings say. */
private val LauschigTvColors = LauschigDarkColors.let { c ->
    tvDarkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
        inversePrimary = c.inversePrimary,
        secondary = c.secondary, onSecondary = c.onSecondary, secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.tertiary, onTertiary = c.onTertiary, tertiaryContainer = c.tertiaryContainer, onTertiaryContainer = c.onTertiaryContainer,
        background = c.background, onBackground = c.onBackground,
        surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceVariant,
        surfaceTint = c.surfaceTint, inverseSurface = c.inverseSurface, inverseOnSurface = c.inverseOnSurface,
        error = c.error, onError = c.onError, errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer,
        border = c.outline, borderVariant = c.outlineVariant, scrim = c.scrim,
    )
}

/**
 * Both themes from the same colours: the TV components (buttons, surfaces, cards) read the TV theme, while the Material 3
 * pieces the TV still uses (text fields, dialogs, progress) read the phone's.
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    Material3Theme(colorScheme = LauschigDarkColors) {
        TvMaterialTheme(colorScheme = LauschigTvColors, content = content)
    }
}

/** Distances of the TV interface. A TV may crop the picture's edge (overscan), so content keeps clear of it. */
object TvDimens {
    val overscanHorizontal = 48.dp
    val overscanVertical = 27.dp
    val cardWidth = 150.dp
    val gap = 16.dp
}
