package io.wolftown.lauschig.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

class PlaybackSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("playback.preferences_pb") } }
    private val settings by lazy { PlaybackSettings(store) }

    @After fun tearDown() = scope.cancel()

    @Test fun seek_defaults_fifteenBack_thirtyForward() = runTest {
        assertEquals(15, settings.seekBackSec.first())
        assertEquals(30, settings.seekForwardSec.first())
    }

    @Test fun seek_choices() {
        assertEquals(listOf(5, 10, 15, 30), PlaybackSettings.SEEK_BACK_CHOICES)
        assertEquals(listOf(15, 30, 45, 60), PlaybackSettings.SEEK_FORWARD_CHOICES)
    }

    @Test fun seek_valuesAreStored_independently() = runTest {
        settings.setSeekBackSec(10)
        settings.setSeekForwardSec(45)
        assertEquals(10, settings.seekBackSec.first())
        assertEquals(45, settings.seekForwardSec.first())
        // A second instance on the same file (a new process) reads the same.
        assertEquals(10, PlaybackSettings(store).seekBackSec.first())
    }

    @Test fun seek_valuesOutsideTheChoices_areIgnored() = runTest {
        settings.setSeekBackSec(10)
        settings.setSeekBackSec(7)
        settings.setSeekForwardSec(5)
        assertEquals(10, settings.seekBackSec.first())
        assertEquals(30, settings.seekForwardSec.first())
    }

    @Test fun seek_aStoredValueNoLongerAChoice_fallsBackToTheDefault() = runTest {
        store.edit { it[intPreferencesKey("seek_back_sec")] = 99; it[intPreferencesKey("seek_forward_sec")] = 1 }
        assertEquals(15, settings.seekBackSec.first())
        assertEquals(30, settings.seekForwardSec.first())
    }
}
