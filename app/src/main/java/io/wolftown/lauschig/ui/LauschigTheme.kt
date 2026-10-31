package io.wolftown.lauschig.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Warm fallback palette (amber / terracotta) used below API 31 or when dynamic color is off.
private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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

@Composable
fun LauschigTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
