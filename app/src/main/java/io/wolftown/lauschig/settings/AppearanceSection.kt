package io.wolftown.lauschig.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wolftown.lauschig.R

/** Callbacks of the "Darstellung" section. */
class AppearanceActions(
    val onTheme: (ThemeMode) -> Unit = {},
    val onPureBlack: (Boolean) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onFontScale: (Int) -> Unit = {},
)

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/**
 * Design, pure black, dynamic colors (Android 12+ only, [dynamicColorAvailable]) and text size. Everything applies at
 * once to the whole app, so this screen is its own preview; the sample card shows title and body text side by side.
 */
@Composable
fun AppearanceSection(
    appearance: Appearance,
    actions: AppearanceActions,
    dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    /** Entry to the tab order / start screen page; rendered by the caller (needs navigation). */
    extra: @Composable () -> Unit = {},
) {
    Section(R.string.settings_section_look)
    ChoiceRow(R.string.settings_theme, R.string.settings_theme_hint, "themeMode", ThemeMode.entries, appearance.theme, actions.onTheme) { themeLabel(it) }
    SwitchRow(
        R.string.settings_pure_black,
        if (appearance.theme == ThemeMode.LIGHT) R.string.settings_pure_black_light else R.string.settings_pure_black_hint,
        appearance.pureBlack, actions.onPureBlack, Modifier.testTag("pureBlack"),
        enabled = appearance.theme != ThemeMode.LIGHT,
    )
    if (dynamicColorAvailable) {
        SwitchRow(R.string.settings_dynamic_color, R.string.settings_dynamic_color_hint, appearance.dynamicColor, actions.onDynamicColor, Modifier.testTag("dynamicColor"))
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_font_scale)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_font_scale_hint))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val steps = Appearance.FONT_SCALES
                    steps.forEachIndexed { i, percent ->
                        SegmentedButton(
                            selected = appearance.fontScalePercent == percent,
                            onClick = { actions.onFontScale(percent) },
                            shape = SegmentedButtonDefaults.itemShape(i, steps.size),
                            label = { Text(stringResource(R.string.font_scale_percent, percent), maxLines = 1) },
                            modifier = Modifier.testTag("fontScale:$percent"),
                        )
                    }
                }
            }
        },
    )
    LookPreview(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    extra()
}

/** A miniature of a title row in the current colors and text size. */
@Composable
private fun LookPreview(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().testTag("lookPreview"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(48.dp)) {}
            Column(Modifier.padding(start = 12.dp)) {
                Text(stringResource(R.string.settings_preview_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_preview_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
