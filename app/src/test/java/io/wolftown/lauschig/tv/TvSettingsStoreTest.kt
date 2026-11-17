package io.wolftown.lauschig.tv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the damaged file: the handler logs through android.util.Log.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TvSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("tv.preferences_pb") } }

    @After fun tearDown() = scope.cancel()

    @Test fun screenOff_isFiveMinutesByDefault() = runTest {
        assertEquals(5, DataStoreTvSettings(store).screenOffMinutes.first())
    }

    @Test fun screenOff_staysStored_includingNever() = runTest {
        DataStoreTvSettings(store).setScreenOffMinutes(15)
        // A second instance on the same file (a new process) reads the same.
        assertEquals(15, DataStoreTvSettings(store).screenOffMinutes.first())
        DataStoreTvSettings(store).setScreenOffMinutes(0)
        assertEquals(0, DataStoreTvSettings(store).screenOffMinutes.first())
    }

    @Test fun aDamagedFile_fallsBackToTheDefault() = runTest {
        val file = tmp.newFile("damaged.preferences_pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02)) }
        val damaged = PreferenceDataStoreFactory.create(corruptionHandler = tvSettingsCorruptionHandler(), scope = scope) { file }
        assertEquals(5, DataStoreTvSettings(damaged).screenOffMinutes.first())
    }
}
