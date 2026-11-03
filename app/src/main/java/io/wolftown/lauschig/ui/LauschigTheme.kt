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

// Warm fallback palette (amber / terracotta) used below API 31 or when dynamic color is off.
internal val LauschigLightColors = lightColorScheme(
    primary = Color(0xFF8F4C00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF745944),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2A1708),
    tertiary = Color(0xFF5E6137),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE3E6B0),
    onTertiaryContainer = Color(0xFF1B1D00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF211A15),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF211A15),
    surfaceVariant = Color(0xFFF3DFD1),
    onSurfaceVariant = Color(0xFF52443A),
    outline = Color(0xFF85746A),
)

internal val LauschigDarkColors = darkColorScheme(
    primary = Color(0xFFFFB878),
    onPrimary = Color(0xFF4B2800),
    primaryContainer = Color(0xFF6C3A00),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE3C0A7),
    onSecondary = Color(0xFF422B1A),
    secondaryContainer = Color(0xFF5B412D),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFC7CA96),
    onTertiary = Color(0xFF30320C),
    tertiaryContainer = Color(0xFF464A22),
    onTertiaryContainer = Color(0xFFE3E6B0),
    background = Color(0xFF19120D),
    onBackground = Color(0xFFEFE0D7),
    surface = Color(0xFF19120D),
    onSurface = Color(0xFFEFE0D7),
    surfaceVariant = Color(0xFF52443A),
    onSurfaceVariant = Color(0xFFD7C3B7),
    outline = Color(0xFF9F8D82),
)

/** True when [appearance] asks for the dark theme (or follows a dark system). */
@Composable
fun isDarkTheme(appearance: Appearance): Boolean = when (appearance.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's color scheme: wallpaper colors ([dynamic]) on Android 12+ when [dynamicColor] is on, else the warm
 * palette; [pureBlack] turns the dark scheme's background black (see [withPureBlack]). Pure function for tests.
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
