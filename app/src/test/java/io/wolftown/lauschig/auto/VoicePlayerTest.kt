package io.wolftown.lauschig.auto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.wolftown.lauschig.account.Account
import io.wolftown.lauschig.auto.CarTestData.S
import io.wolftown.lauschig.catalog.Catalog
import io.wolftown.lauschig.connection.ConnectionState
import io.wolftown.lauschig.connection.ServerAddresses
import io.wolftown.lauschig.db.LauschigDatabase
import io.wolftown.lauschig.net.ApiProvider
import io.wolftown.lauschig.net.FakeAccountStore
import io.wolftown.lauschig.playback.FakeLastPlayedStore
import io.wolftown.lauschig.playback.LastTitleStarter
import io.wolftown.lauschig.playback.PlayerController
import io.wolftown.lauschig.playback.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** "Play X in Lauschig": the title found is played, and whoever asked hears of it (the TV opens its player then). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoicePlayerTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LauschigDatabase::class.java)
        .allowMainThreadQueries().build()
    private val accounts = FakeAccountStore().apply { acc.value = Account(S, "u", "n", false, ServerAddresses(null, null), emptySet()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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

    private fun voice(): VoicePlayer {
        val library = CarLibrary(db.itemDao(), db.libraryDao(), db.progressDao(), db.episodeDao(), db.downloadDao(), db.catalogDao(), Catalog(), TestCarTexts)
        val api = ApiProvider(flowOf(ConnectionState.Online("https://abs.example.org/".toHttpUrl(), ConnectionState.Via.LOCAL)), OkHttpClient(), accounts, scope)
        val last = LastTitleStarter(accounts, FakeLastPlayedStore(), db.progressDao(), db.itemDao(), db.episodeDao(), player)
        return VoicePlayer(CarSearch(library), accounts, api, last, player)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** The search runs on the main thread and on Room's: lets both run until [done] or [maxMs] have passed. */
    private fun runUntil(maxMs: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + maxMs
        while (!done() && System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    @Test fun aTitleFound_isPlayed_andThenSaidSo() {
        runBlocking { CarTestData.seed(db) }
        var playing = 0
        voice().playFromSearch("Mehrteilig", null) { playing++ }
        runUntil { playing > 0 }
        assertEquals(listOf("multi"), plays)
        assertEquals(1, playing)
    }

    @Test fun nothingFound_playsNothing_andSaysNothing() {
        runBlocking { CarTestData.seed(db) }
        var playing = 0
        voice().playFromSearch("zzz unbekannt", null) { playing++ }
        // Nothing to wait for that would say it is over: a second is far more than the search over the seeded titles takes.
        runUntil(maxMs = 1_000) { false }
        assertEquals(emptyList<String>(), plays)
        assertEquals(0, playing)
    }
}
