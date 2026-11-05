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

    /** Review I2: B keeps its own title; what A heard on this device is A's. */
    @Test fun aDeviceTitle_isTheLastTitleOfTheAccountThatPlayedIt() = runBlocking {
        store.set(LastPlayed(b, "b-book", 100))
        store.set(LastPlayed(a, "a-book", 150))
        store.set(LastPlayed(dev, "local:x", 200), account = a)
        assertEquals(LastPlayed(dev, "local:x", 200), store.forAccount(a))
        assertEquals("B's own stays B's", LastPlayed(b, "b-book", 100), store.forAccount(b))
        store.set(LastPlayed(a, "a-book", 300))
        assertEquals(LastPlayed(a, "a-book", 300), store.forAccount(a))
        store.set(LastPlayed(dev, "local:y", 400), account = b)
        assertEquals(LastPlayed(dev, "local:y", 400), store.forAccount(b))
        assertEquals(LastPlayed(a, "a-book", 300), store.forAccount(a))
    }

    @Test fun oneAccount_seesTheNewestOfBothAsBefore() = runBlocking {
        store.set(LastPlayed(a, "a-book", 10))
        store.set(LastPlayed(dev, "local:x", 20), account = a)
        assertEquals(LastPlayed(dev, "local:x", 20), store.forAccount(a))
        store.set(LastPlayed(a, "a-book", 30))
        assertEquals(LastPlayed(a, "a-book", 30), store.forAccount(a))
    }

    @Test fun anAccountWithoutAnyOfItsOwn_getsTheSharedEntry_fromBeforeTheFirstNewWrite() = runBlocking {
        store.set(LastPlayed(dev, "local:old", 5)) // an old value, or played signed out
        assertEquals(LastPlayed(dev, "local:old", 5), store.forAccount(a))
        store.set(LastPlayed(b, "b-book", 3)) // older than the shared one, and still B's own
        assertEquals(LastPlayed(b, "b-book", 3), store.forAccount(b))
        assertNull("the first new write ends the fallback", store.forAccount(a))
    }

    /** As in 0.4.0, where the one entry held the last title: it was a server title, so a sign-out leaves nothing to resume. */
    @Test fun aSignOut_whoseLastTitleWasAServerTitle_leavesNothingToResume() = runBlocking {
        store.set(LastPlayed(dev, "local:old", 1)) // from before
        store.set(LastPlayed(dev, "local:x", 10), account = a)
        store.set(LastPlayed(a, "a-book", 20))
        store.forget(a)
        assertNull(store.forAccount(a))
        assertNull(store.forAccount(b))
    }

    /** As before: signing out leaves the device title for whoever signs in next, an account's own server title goes. */
    @Test fun aSignOut_handsItsDeviceTitleToTheDevice() = runBlocking {
        store.set(LastPlayed(a, "a-book", 10))
        store.set(LastPlayed(dev, "local:x", 20), account = a)
        store.forget(a)
        assertNull(store.get(a))
        assertNull(store.deviceTitleOf(a))
        assertEquals(LastPlayed(dev, "local:x", 20), store.forAccount(a))
        assertEquals(LastPlayed(dev, "local:x", 20), store.forAccount(b))
    }

    @Test fun aSignOut_doesNotOverwriteANewerSharedEntry() = runBlocking {
        store.set(LastPlayed(dev, "local:x", 20), account = a)
        store.set(LastPlayed(dev, "local:newer", 30))
        store.forget(a)
        assertEquals(LastPlayed(dev, "local:newer", 30), store.get(dev))
    }

    @Test fun forgetting_goesOnlyForThatAccount_theDeviceTitleStays() = runBlocking {
        store.set(LastPlayed(a, "a-book", 10))
        store.set(LastPlayed(b, "b-book", 11))
        store.set(LastPlayed(dev, "local:x", 5))
        store.forget(a)
        assertNull(store.get(a))
        assertEquals(LastPlayed(b, "b-book", 11), store.get(b))
        assertEquals(LastPlayed(dev, "local:x", 5), store.forAccount(a))
        assertEquals("B keeps its own", LastPlayed(b, "b-book", 11), store.forAccount(b))
    }

    // Review N3: the device's own entry does not hand one person's title to another.

    @Test fun aRemovedProfile_handsNothingOver() = runBlocking {
        store.set(LastPlayed(b, "b-book", 10))
        store.set(LastPlayed(dev, "local:x", 20), account = b)
        store.drop(b)
        assertNull(store.get(b))
        assertNull(store.deviceTitleOf(b))
        assertNull(store.get(dev))
        assertNull(store.forAccount(a))
    }

    @Test fun droppingAProfile_leavesEveryoneElsesEntries() = runBlocking {
        store.set(LastPlayed(a, "a-book", 5))
        store.set(LastPlayed(dev, "local:a", 6), account = a)
        store.set(LastPlayed(dev, "local:b", 7), account = b)
        store.set(LastPlayed(dev, "local:old", 1))
        store.drop(b)
        assertEquals(LastPlayed(a, "a-book", 5), store.get(a))
        assertEquals(LastPlayed(dev, "local:a", 6), store.deviceTitleOf(a))
        assertEquals(LastPlayed(dev, "local:old", 1), store.get(dev))
    }

    @Test fun theDevicesEntry_becomesTheActiveAccounts_ifItHasNone() = runBlocking {
        store.set(LastPlayed(dev, "local:old", 5))
        store.adoptDeviceEntry(a)
        assertEquals(LastPlayed(dev, "local:old", 5), store.deviceTitleOf(a))
        assertNull(store.get(dev))
        assertEquals(LastPlayed(dev, "local:old", 5), store.forAccount(a))
        assertNull("the next one does not get it", store.forAccount(b))
    }

    @Test fun theDevicesEntry_staysWhereItIs_ifTheAccountHasADeviceTitle() = runBlocking {
        store.set(LastPlayed(dev, "local:own", 9), account = a)
        store.set(LastPlayed(dev, "local:old", 5))
        store.adoptDeviceEntry(a)
        assertEquals(LastPlayed(dev, "local:own", 9), store.deviceTitleOf(a))
        assertEquals(LastPlayed(dev, "local:old", 5), store.get(dev))
    }

    @Test fun nothingToAdopt_changesNothing() = runBlocking {
        store.adoptDeviceEntry(a)
        assertNull(store.deviceTitleOf(a))
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
