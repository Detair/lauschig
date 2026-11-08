package io.wolftown.lauschig.playback

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.db.entities.ItemEntity
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Headphones on this device say nothing about a receiver somewhere else: nothing starts on the phone during a cast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutoResumerCastTest {
    @get:Rule val tmp = TemporaryFolder()
    private val s = "aaaaaaaaaaaaaaaa"
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(context, LauschigDatabase::class.java).allowMainThreadQueries().build()
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
    private val headphones = listOf(7 to AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    private fun resumer(): AutoResumer {
        val store = FakeLastPlayedStore().apply { value = LastPlayed(s, "it", System.currentTimeMillis() - 60_000) }
        runBlocking { db.itemDao().upsertDetails(ItemEntity(s, "it", "l", "T", durationSec = 100.0)) }
        val settings = PlaybackSettings(PreferenceDataStoreFactory.create(scope = background) { tmp.newFile("playback.preferences_pb") })
        return AutoResumer(context, settings, LastTitleStarter(accounts, store, db.progressDao(), db.itemDao(), db.episodeDao(), player), player)
    }

    @After fun tearDown() {
        db.close()
        background.cancel()
    }

    @Test fun onThisDevice_theHeadphonesResumeTheTitle() = runBlocking {
        resumer().onAdded(headphones, enabled = true)
        assertEquals(listOf("it"), plays)
    }

    @Test fun whileCasting_theyStartNothing() = runBlocking {
        player.state.value = PlayerUiState(outputName = "Wohnzimmer")
        resumer().onAdded(headphones, enabled = true)
        assertEquals(emptyList<String>(), plays)
    }
}
