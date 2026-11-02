package io.wolftown.lauschig.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.net.FakeAccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @After fun tearDown() = db.close()

    @Test fun resumesTheNewestUnfinishedTitle_notAFinishedOne() = runTest {
        db.progressDao().saveLocal(s, "old", 10.0, 100.0, false, 1_000)
        db.progressDao().saveLocal(s, "ep~1", 10.0, 100.0, false, 3_000)
        db.progressDao().saveLocal(s, "done", 100.0, 100.0, true, 9_000)
        val starter = LastTitleStarter(accounts, db.progressDao(), player)
        assertEquals(LastTitleStarter.Last(s, "ep~1", 3_000), starter.last())
        assertTrue(starter.resumeLast())
        assertEquals(listOf("ep~1"), plays)
    }

    @Test fun doesNothingWhilePlayingOrWithoutTitle() = runTest {
        val starter = LastTitleStarter(accounts, db.progressDao(), player)
        assertFalse(starter.resumeLast())
        db.progressDao().saveLocal(s, "a", 10.0, 100.0, false, 1_000)
        player.state.value = PlayerUiState(itemId = "x", isPlaying = true)
        assertFalse(starter.resumeLast())
        assertEquals(emptyList<String>(), plays)
    }
}
