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
}
