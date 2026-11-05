package io.wolftown.lauschig.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UiSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("ui.preferences_pb") } }
    private val settings by lazy { UiSettings(store) }

    @After fun tearDown() = scope.cancel()

    @Test fun defaults_matchTheAppBefore() = runTest {
        assertEquals(Appearance(ThemeMode.SYSTEM, pureBlack = false, dynamicColor = true, fontScalePercent = 100), settings.appearance.first())
        assertEquals(DarkThemeTone.NIGHT_BLUE, settings.appearance.first().darkTone)
    }

    @Test fun darkTone_isStored_unknownFallsBackToNightBlue() = runTest {
        settings.setDarkTone(DarkThemeTone.WARM_AMBER)
        assertEquals(DarkThemeTone.WARM_AMBER, UiSettings(store).appearance.first().darkTone)
        store.edit { it[stringPreferencesKey("dark_tone")] = "SEPIA" }
        assertEquals(DarkThemeTone.NIGHT_BLUE, settings.appearance.first().darkTone)
    }

    @Test fun everyAppearanceValue_isStored() = runTest {
        settings.setTheme(ThemeMode.DARK)
        settings.setPureBlack(true)
        settings.setDynamicColor(false)
        settings.setFontScale(130)
        assertEquals(Appearance(ThemeMode.DARK, pureBlack = true, dynamicColor = false, fontScalePercent = 130), settings.appearance.first())
        // A second instance on the same file (a new process) reads the same.
        assertEquals(130, UiSettings(store).appearance.first().fontScalePercent)
    }

    @Test fun fontScale_onlyTheOfferedSteps() = runTest {
        settings.setFontScale(115)
        settings.setFontScale(300)
        assertEquals(115, settings.appearance.first().fontScalePercent)
    }

    @Test fun unknownStoredValues_fallBackToDefaults() = runTest {
        store.edit {
            it[stringPreferencesKey("theme_mode")] = "SEPIA"
            it[intPreferencesKey("font_scale_percent")] = 42
        }
        val a = settings.appearance.first()
        assertEquals(ThemeMode.SYSTEM, a.theme)
        assertEquals(100, a.fontScalePercent)
    }

    @Test fun tabLayout_defaultAndStored() = runTest {
        assertEquals(io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT, settings.tabLayout.first())
        val custom = io.wolftown.lauschig.ui.nav.TabLayout.DEFAULT
            .moved(io.wolftown.lauschig.ui.TopLevelTab.Search, -2)
            .withHidden(io.wolftown.lauschig.ui.TopLevelTab.Discover, true)
            .withStart(io.wolftown.lauschig.ui.TopLevelTab.Library)
        settings.setTabLayout(custom)
        assertEquals(custom, UiSettings(store).tabLayout.first())
    }

    @Test fun playerRemainingTime_chapterByDefault_bookStored() = runTest {
        assertEquals(RemainingTime.CHAPTER, settings.playerRemainingTime.first())
        settings.setPlayerRemainingTime(RemainingTime.BOOK)
        assertEquals(RemainingTime.BOOK, UiSettings(store).playerRemainingTime.first())
    }

    @Test fun playerRemainingTime_unknownStoredValue_isChapter() = runTest {
        store.edit { it[stringPreferencesKey("player_remaining_time")] = "WEEK" }
        assertEquals(RemainingTime.CHAPTER, settings.playerRemainingTime.first())
    }
}
