package io.wolftown.lauschig.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.wolftown.lauschig.ui.nav.TabLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiDataStore by preferencesDataStore(name = "ui_settings")

/** Light, dark, or whatever the system uses. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the player's right timestamp counts down: the current chapter or the whole book. */
enum class RemainingTime { CHAPTER, BOOK }

/**
 * The dark theme's palette. [NIGHT_BLUE] is the regular one: the wallpaper's colours when dynamic colour is on (Android
 * 12+), else Lauschig's night blue. [WARM_AMBER] ("Kaminfeuer & Bernstein") is warm mocha, cream and honey amber for
 * the bed at night; picked, it wins over dynamic colour in the dark theme (wallpaper palettes are often cool), and pure
 * black still darkens it. The light theme is never affected.
 */
enum class DarkThemeTone { NIGHT_BLUE, WARM_AMBER }

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
    /** Dark theme only: night blue (or the wallpaper's colours) or the warm fireside palette. */
    val darkTone: DarkThemeTone = DarkThemeTone.NIGHT_BLUE,
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
            darkTone = prefs[DARK_TONE]?.let { runCatching { DarkThemeTone.valueOf(it) }.getOrNull() } ?: DarkThemeTone.NIGHT_BLUE,
        )
    }.distinctUntilChanged()

    /** Order, visibility and start screen of the tabs; normalised on read (see [TabLayout.of]). */
    val tabLayout: Flow<TabLayout> = store.data.map { prefs ->
        TabLayout.of(prefs[TAB_ORDER]?.split(','), prefs[TABS_HIDDEN], prefs[START_TAB])
    }.distinctUntilChanged()

    suspend fun setTabLayout(layout: TabLayout) = updateTabLayout { layout }

    /** Read-modify-write in one DataStore transaction, so quick taps never work on a stale layout. */
    suspend fun updateTabLayout(change: (TabLayout) -> TabLayout) {
        store.edit {
            val layout = change(TabLayout.of(it[TAB_ORDER]?.split(','), it[TABS_HIDDEN], it[START_TAB]))
            it[TAB_ORDER] = layout.order.joinToString(",") { t -> t.name }
            it[TABS_HIDDEN] = layout.hidden.mapTo(HashSet()) { t -> t.name }
            it[START_TAB] = layout.start.name
        }
    }

    /** The player's right timestamp (tap toggles it); the current chapter by default. */
    val playerRemainingTime: Flow<RemainingTime> = store.data.map { prefs ->
        prefs[REMAINING_TIME]?.let { runCatching { RemainingTime.valueOf(it) }.getOrNull() } ?: RemainingTime.CHAPTER
    }.distinctUntilChanged()

    suspend fun setPlayerRemainingTime(mode: RemainingTime) {
        store.edit { it[REMAINING_TIME] = mode.name }
    }

    /** The versionCode whose "Was ist neu" was seen (or skipped on a first install); null before the first check. */
    val lastSeenVersion: Flow<Int?> = store.data.map { it[LAST_SEEN_VERSION] }.distinctUntilChanged()

    suspend fun setLastSeenVersion(code: Int) {
        store.edit { it[LAST_SEEN_VERSION] = code }
    }

    /** The welcome of the very first start was seen to its end or skipped. */
    val welcomeSeen: Flow<Boolean> = store.data.map { it[WELCOME_SEEN] ?: false }.distinctUntilChanged()

    suspend fun setWelcomeSeen() {
        store.edit { it[WELCOME_SEEN] = true }
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setPureBlack(on: Boolean) {
        store.edit { it[PURE_BLACK] = on }
    }

    suspend fun setDynamicColor(on: Boolean) {
        store.edit { it[DYNAMIC_COLOR] = on }
    }

    suspend fun setDarkTone(tone: DarkThemeTone) {
        store.edit { it[DARK_TONE] = tone.name }
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
        val DARK_TONE = stringPreferencesKey("dark_tone")
        val FONT_SCALE = intPreferencesKey("font_scale_percent")
        val TAB_ORDER = stringPreferencesKey("tab_order")
        val TABS_HIDDEN = stringSetPreferencesKey("tabs_hidden")
        val START_TAB = stringPreferencesKey("start_tab")
        val LAST_SEEN_VERSION = intPreferencesKey("whats_new_seen_version")
        val REMAINING_TIME = stringPreferencesKey("player_remaining_time")
        val WELCOME_SEEN = booleanPreferencesKey("welcome_seen")
    }
}
