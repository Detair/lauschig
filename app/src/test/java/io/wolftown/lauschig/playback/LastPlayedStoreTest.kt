package io.wolftown.lauschig.playback

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.local.DeviceLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The title last played, per account (Phase 7, Task 1.2); the device title stays the device's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastPlayedStoreTest {
    private val a = "aaaaaaaaaaaaaaaa"
    private val b = "bbbbbbbbbbbbbbbb"
    private val dev = DeviceLibrary.SERVER_ID
    private val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "last-${System.nanoTime()}.preferences_pb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { DataStoreLastPlayedStore(PreferenceDataStoreFactory.create(migrations = listOf(LastPlayedMigration), scope = scope) { file }) }

    @After fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test fun eachAccountHasItsOwnTitle() = runBlocking {
        store.set(LastPlayed(a, "a-book", 1))
        store.set(LastPlayed(b, "b-book", 2))
        assertEquals(LastPlayed(a, "a-book", 1), store.forAccount(a))
        assertEquals(LastPlayed(b, "b-book", 2), store.forAccount(b))
    }

    @Test fun aDeviceTitle_isEveryAccounts_whenItIsTheNewest() = runBlocking {
        store.set(LastPlayed(a, "a-book", 10))
        store.set(LastPlayed(dev, "local:x", 20))
        assertEquals(LastPlayed(dev, "local:x", 20), store.forAccount(a))
        assertEquals("without a title of its own, too", LastPlayed(dev, "local:x", 20), store.forAccount(b))
        store.set(LastPlayed(a, "a-book", 30))
        assertEquals(LastPlayed(a, "a-book", 30), store.forAccount(a))
        assertEquals(LastPlayed(a, "a-book", 30), store.get(a))
    }

    @Test fun forgetting_goesOnlyForThatAccount_theDeviceTitleStays() = runBlocking {
        store.set(LastPlayed(a, "a-book", 10))
        store.set(LastPlayed(b, "b-book", 11))
        store.set(LastPlayed(dev, "local:x", 5))
        store.forget(a)
        assertNull(store.get(a))
        assertEquals(LastPlayed(b, "b-book", 11), store.get(b))
        assertEquals(LastPlayed(dev, "local:x", 5), store.forAccount(a))
    }

    /** What 0.4.0 stored: one title under three keys. */
    private suspend fun oldVersionStored(server: String, item: String, at: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        PreferenceDataStoreFactory.create(scope = scope) { file }.edit {
            it[stringPreferencesKey("server_id")] = server
            it[stringPreferencesKey("item_id")] = item
            it[longPreferencesKey("paused_at")] = at
        }
        scope.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
    }

    @Test fun upgrade_theOldTitleStays_withItsServer() = runBlocking {
        oldVersionStored(a, "a-book", 7)
        assertEquals(LastPlayed(a, "a-book", 7), store.forAccount(a))
        assertNull(store.forAccount(b))
    }

    @Test fun upgrade_anOldDeviceTitleStays_theDevicesTitle() = runBlocking {
        oldVersionStored(dev, "local:x", 7)
        assertEquals(LastPlayed(dev, "local:x", 7), store.forAccount(a))
    }
}
