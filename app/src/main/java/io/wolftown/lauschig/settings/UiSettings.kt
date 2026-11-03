package io.wolftown.lauschig.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiDataStore by preferencesDataStore(name = "ui_settings")

/** Light, dark, or whatever the system uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How the app looks (Einstellungen → Darstellung). Defaults are the look the app had before the setting existed. */
@Immutable
data class Appearance(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Dark theme only: black background and surfaces (OLED, night). */
    val pureBlack: Boolean = false,
    /** Wallpaper colors on Android 12+. */
    val dynamicColor: Boolean = true,
    /** App text size on top of the system's, in percent (one of [FONT_SCALES]). */
    val fontScalePercent: Int = 100,
) {
    companion object {
        val FONT_SCALES = listOf(85, 100, 115, 130)
    }
}

/** Look and navigation of the app; device-local only. */
@Singleton
class UiSettings(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.uiDataStore)

    val appearance: Flow<Appearance> = store.data.map { prefs ->
        Appearance(
            theme = prefs[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            pureBlack = prefs[PURE_BLACK] ?: false,
            dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            fontScalePercent = prefs[FONT_SCALE]?.takeIf { it in Appearance.FONT_SCALES } ?: 100,
        )
    }.distinctUntilChanged()

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setPureBlack(on: Boolean) {
        store.edit { it[PURE_BLACK] = on }
    }

    suspend fun setDynamicColor(on: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = on }
    }

    /** Ignores values that are not one of [Appearance.FONT_SCALES]. */
    suspend fun setFontScale(percent: Int) {
        if (percent !in Appearance.FONT_SCALES) return
        store.edit { it[FONT_SCALE] = percent }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FONT_SCALE = intPreferencesKey("font_scale_percent")
    }
}
