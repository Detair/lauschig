package io.wolftown.lauschig.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import io.wolftown.lauschig.settings.Appearance
import io.wolftown.lauschig.settings.ThemeMode

/*
 * Lauschig's own calm palette for when the wallpaper's colours are not used (Android below 12, or "dynamic colour" off):
 * a night-blue evening with a honey-amber light in dark, morning mist with a deeper amber in light; slate as the
 * secondary, moss as the tertiary colour (in light one shade darker, so they read as text on every container). Every
 * M3 role is set (no baseline purple left), the surface containers step in one direction, text roles keep WCAG AA on
 * the surface and every container, the on-colours on their roles, and the colour containers (the inactive slider track
 * is secondaryContainer) stand out from the surface by at least 1.3:1 (see ColorSchemeTest).
 */
internal val LauschigLightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFCB80),
    onPrimaryContainer = Color(0xFF2B1A00),
    inversePrimary = Color(0xFFF0B44C),
    secondary = Color(0xFF4F5E7D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8D2F0),
    onSecondaryContainer = Color(0xFF172140),
    tertiary = Color(0xFF436B57),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFAEDDBD),
    onTertiaryContainer = Color(0xFF0A2618),
    background = Color(0xFFEEF1F6),
    onBackground = Color(0xFF191C24),
    surface = Color(0xFFEEF1F6),
    onSurface = Color(0xFF191C24),
    surfaceVariant = Color(0xFFDCE1EC),
    onSurfaceVariant = Color(0xFF444A5A),
    surfaceTint = Color(0xFF8A5A00),
    inverseSurface = Color(0xFF2D3140),
    inverseOnSurface = Color(0xFFEEF1F6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF737A8C),
    outlineVariant = Color(0xFFC3C8D5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFE5E9F0),
    surfaceContainerHigh = Color(0xFFE1E5ED),
    surfaceContainerHighest = Color(0xFFDDE2EA),
    surfaceContainerLow = Color(0xFFE9EDF3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD3D8E1),
    primaryFixed = Color(0xFFFFDEAD),
    primaryFixedDim = Color(0xFFF0B44C),
    onPrimaryFixed = Color(0xFF281800),
    onPrimaryFixedVariant = Color(0xFF5E4100),
    secondaryFixed = Color(0xFFDAE2FF),
    secondaryFixedDim = Color(0xFFBCC6E3),
    onSecondaryFixed = Color(0xFF111B33),
    onSecondaryFixedVariant = Color(0xFF3D4864),
    tertiaryFixed = Color(0xFFC6EDD3),
    tertiaryFixedDim = Color(0xFFA6D0B6),
    onTertiaryFixed = Color(0xFF002113),
    onTertiaryFixedVariant = Color(0xFF284E3B),
)

internal val LauschigDarkColors = darkColorScheme(
    primary = Color(0xFFF0B44C),
    onPrimary = Color(0xFF422C00),
    primaryContainer = Color(0xFF5E4100),
    onPrimaryContainer = Color(0xFFFFDEAD),
    inversePrimary = Color(0xFF8A5A00),
    secondary = Color(0xFFBCC6E3),
    onSecondary = Color(0xFF26304A),
    secondaryContainer = Color(0xFF5B6B8C),
    onSecondaryContainer = Color(0xFFF2F4FF),
    tertiary = Color(0xFFA6D0B6),
    onTertiary = Color(0xFF0F3824),
    tertiaryContainer = Color(0xFF4F7A64),
    onTertiaryContainer = Color(0xFFEFFFF4),
    background = Color(0xFF18213A),
    onBackground = Color(0xFFE2E6F2),
    surface = Color(0xFF18213A),
    onSurface = Color(0xFFE2E6F2),
    surfaceVariant = Color(0xFF3A4560),
    onSurfaceVariant = Color(0xFFC3C9DA),
    surfaceTint = Color(0xFFF0B44C),
    inverseSurface = Color(0xFFE2E6F2),
    inverseOnSurface = Color(0xFF2B3248),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8D94A8),
    outlineVariant = Color(0xFF424B66),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B4566),
    // Ink (#263257) is the dark containers' colour: cards, sheets, the mini player.
    surfaceContainer = Color(0xFF212C48),
    surfaceContainerHigh = Color(0xFF263257),
    surfaceContainerHighest = Color(0xFF303C63),
    surfaceContainerLow = Color(0xFF1D2741),
    surfaceContainerLowest = Color(0xFF121A30),
    surfaceDim = Color(0xFF18213A),
    primaryFixed = Color(0xFFFFDEAD),
    primaryFixedDim = Color(0xFFF0B44C),
    onPrimaryFixed = Color(0xFF281800),
    onPrimaryFixedVariant = Color(0xFF5E4100),
    secondaryFixed = Color(0xFFDAE2FF),
    secondaryFixedDim = Color(0xFFBCC6E3),
    onSecondaryFixed = Color(0xFF111B33),
    onSecondaryFixedVariant = Color(0xFF3D4864),
    tertiaryFixed = Color(0xFFC6EDD3),
    tertiaryFixedDim = Color(0xFFA6D0B6),
    onTertiaryFixed = Color(0xFF002113),
    onTertiaryFixedVariant = Color(0xFF284E3B),
)

/** True when [appearance] asks for the dark theme (or follows a dark system). */
@Composable
fun isDarkTheme(appearance: Appearance): Boolean = when (appearance.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's color scheme: wallpaper colors ([dynamic]) on Android 12+ when [dynamicColor] is on, else Lauschig's own
 * calm palette; [pureBlack] turns the dark scheme's background black (see [withPureBlack]). Pure function for tests.
 */
fun colorSchemeFor(
    dark: Boolean,
    pureBlack: Boolean,
    dynamicColor: Boolean,
    sdkInt: Int,
    dynamic: (dark: Boolean) -> ColorScheme,
): ColorScheme {
    val base = when {
        dynamicColor && sdkInt >= Build.VERSION_CODES.S -> dynamic(dark)
        dark -> LauschigDarkColors
        else -> LauschigLightColors
    }
    return if (dark && pureBlack) base.withPureBlack() else base
}

/**
 * Black background and surfaces for OLED screens and the dark bedroom. The surface containers keep their own tint but
 * are pulled towards black, each step a little less, so cards, sheets and the mini player still stand out.
 */
fun ColorScheme.withPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.6f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.5f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.4f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.3f),
    surfaceBright = lerp(surfaceBright, Color.Black, 0.3f),
)

@Composable
fun LauschigTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = colorSchemeFor(
        dark = isDarkTheme(appearance),
        pureBlack = appearance.pureBlack,
        dynamicColor = appearance.dynamicColor,
        sdkInt = Build.VERSION.SDK_INT,
        dynamic = { dark ->
            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> if (dark) LauschigDarkColors else LauschigLightColors // never asked below 12
                dark -> dynamicDarkColorScheme(context)
                else -> dynamicLightColorScheme(context)
            }
        },
    )
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Paints the activity window in the theme's background, so frames without Compose content (after the splash, on
 * rotation, `recreate()`, transitions) match the app – black in pure-black mode instead of the platform theme's grey.
 */
@Composable
fun WindowBackground() {
    val color = MaterialTheme.colorScheme.background
    val view = LocalView.current
    DisposableEffect(color) {
        view.context.findActivity()?.window?.setBackgroundDrawable(color.toArgb().toDrawable())
        onDispose {}
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
