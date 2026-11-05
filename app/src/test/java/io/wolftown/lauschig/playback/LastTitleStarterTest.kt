package io.wolftown.lauschig.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.local.DeviceLibrary
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastTitleStarterTest {
    private val s = "aaaaaaaaaaaaaaaa"
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java).allowMainThreadQueries().build()
    private val accounts = FakeAccountStore().apply { acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private val plays = mutableListOf<String>()
    private val player = object : PlayerController {
        override val state = MutableStateFlow(PlayerUiState())
        override fun play(serverId: String, itemId: String, startSec: Double?) { plays += itemId }
        override fun togglePlayPause() {}
        override fun pause() {}
        override fun seekBy(deltaSec: Int) {}
        override fun seekTo(globalSec: Double) {}
        override fun seekToChapter(index: Int) {}
        override fun setSpeed(speed: Float) {}
    }

    private val store = FakeLastPlayedStore()

    private fun starter() = LastTitleStarter(accounts, store, db.progressDao(), db.itemDao(), db.episodeDao(), player)

    @After fun tearDown() = db.close()

    private suspend fun book(id: String, orphaned: Boolean = false) {
        db.itemDao().upsertDetails(io.wolftown.lauschig.db.entities.ItemEntity(s, id, "l", "T$id", durationSec = 100.0))
        if (orphaned) db.itemDao().setOrphaned(s, listOf(id), true)
    }

    @Test fun lastTitle_isTheOnePlayedOnThisDevice_notTheNewestProgressRow() = runTest {
        book("mine"); book("other")
        db.progressDao().saveLocal(s, "mine", 10.0, 100.0, false, 1_000)
        db.progressDao().mergeFromServer(listOf(io.wolftown.lauschig.db.entities.ProgressEntity(s, "other", 20.0, 100.0, false, 9_000)))
        store.value = LastPlayed(s, "mine", 5_000)
        assertEquals(LastTitleStarter.Last(s, "mine", 5_000), starter().last())
        assertTrue(starter().resumeLast(now = 6_000))
        assertEquals(listOf("mine"), plays)
    }

    @Test fun skipsGoneOrphanedFinishedHiddenOrOtherServer() = runTest {
        store.value = LastPlayed(s, "gone", 5_000)
        assertNull(starter().last())
        book("orph", orphaned = true)
        store.value = LastPlayed(s, "orph", 5_000)
        assertNull(starter().last())
        book("fin")
        db.progressDao().saveLocal(s, "fin", 100.0, 100.0, true, 1)
        store.value = LastPlayed(s, "fin", 5_000)
        assertNull(starter().last())
        book("hid")
        db.progressDao().saveUserAction(io.wolftown.lauschig.db.entities.ProgressEntity(s, "hid", 10.0, 100.0, false, 1, hideFromContinue = true))
        store.value = LastPlayed(s, "hid", 5_000)
        assertNull(starter().last())
        book("x")
        store.value = LastPlayed("bbbbbbbbbbbbbbbb", "x", 5_000)
        assertNull(starter().last())
    }

    @Test fun episodeKey_needsItsEpisodeRow() = runTest {
        book("pod")
        store.value = LastPlayed(s, "pod~e1", 5_000)
        assertNull(starter().last())
        db.episodeDao().upsert(listOf(io.wolftown.lauschig.db.entities.EpisodeEntity(s, "pod", "e1", "Folge")))
        assertEquals("pod~e1", starter().last()?.itemId)
    }

    @Test fun maxAge_andPlayingState() = runTest {
        book("a")
        store.value = LastPlayed(s, "a", 1_000)
        assertFalse(starter().resumeLast(maxAgeMs = 10_000, now = 20_000))
        player.state.value = PlayerUiState(itemId = "x", isPlaying = true)
        assertFalse(starter().resumeLast(now = 2_000))
        assertEquals(emptyList<String>(), plays)
    }

    @Test fun signedOut_nothingResumes_aDeviceTitleOnlyWhileSignedIn() = runTest {
        val local = "local:0123456789abcdef01234567"
        db.itemDao().upsertFromList(listOf(io.wolftown.lauschig.db.entities.ItemEntity(DeviceLibrary.SERVER_ID, local, DeviceLibrary.LIBRARY_ID, "Lokal")))
        db.progressDao().saveLocal(DeviceLibrary.SERVER_ID, local, 30.0, 600.0, false, 1)
        store.value = LastPlayed(DeviceLibrary.SERVER_ID, local, 5_000)
        assertEquals(LastTitleStarter.Last(DeviceLibrary.SERVER_ID, local, 5_000), starter().last())
        // Signed out only Setup is on the phone: play keys, the car and headphones resume nothing.
        accounts.acc.value = null
        assertNull(starter().last())
        book("srv")
        store.value = LastPlayed(s, "srv", 5_000)
        assertNull(starter().last())
    }

    @Test fun afterASwitch_eachAccountHasItsOwnLastTitle_andNothingStartsByItself() = runTest {
        val b = "bbbbbbbbbbbbbbbb"
        book("mine")
        db.itemDao().upsertDetails(io.wolftown.lauschig.db.entities.ItemEntity(b, "theirs", "l", "T", durationSec = 100.0))
        store.set(LastPlayed(s, "mine", 5_000))
        store.set(LastPlayed(b, "theirs", 6_000))
        assertEquals("mine", starter().last()!!.itemId)
        accounts.acc.value = Account(b, "u2", "m", false, ServerAddresses(null, null), emptySet())
        assertEquals("theirs", starter().last()!!.itemId)
        accounts.acc.value = Account(s, "u", "n", false, ServerAddresses(null, null), emptySet())
        assertEquals("mine", starter().last()!!.itemId)
        assertEquals(emptyList<String>(), plays)
    }
}
